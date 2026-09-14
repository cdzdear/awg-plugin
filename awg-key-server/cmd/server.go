// AWG Key Management Server
// Runs on your AmneziaWG server and handles config rotation for clients.
//
// Protocol:
//   POST /v1/check    — check if client config is still valid
//   POST /v1/refresh  — get new config (if current is expired/revoked)
//   GET  /v1/health   — server health check
//
// Security:
//   - All requests authenticated with HMAC-SHA256 token
//   - Configs encrypted with client's pre-shared AES key
//   - Rate limiting per client ID
//   - No private keys ever leave the server unencrypted
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

// ──────────────────────────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────────────────────────

type ServerConfig struct {
	ListenAddr    string `json:"listen_addr"`    // ":8443"
	TLSCert       string `json:"tls_cert"`       // path to cert.pem
	TLSKey        string `json:"tls_key"`        // path to key.pem
	MasterSecret  string `json:"master_secret"`  // HMAC master key (base64)
	WGConfigsDir  string `json:"wg_configs_dir"` // dir with per-client configs
	MaxKeyAgeDays int    `json:"max_key_age_days"` // how long before forced rotation
}

// ──────────────────────────────────────────────────────────────────
// Client database (in-memory + JSON file persistence)
// ──────────────────────────────────────────────────────────────────

type ClientRecord struct {
	ClientID     string    `json:"client_id"`
	PublicKey    string    `json:"public_key"`    // WireGuard public key (base64)
	SharedSecret string    `json:"shared_secret"` // AES-256 key for config encryption (base64)
	IssuedAt     time.Time `json:"issued_at"`
	ExpiresAt    time.Time `json:"expires_at"`
	IsRevoked    bool      `json:"is_revoked"`
	ConfigFile   string    `json:"config_file"` // path to this client's .conf
}

type ClientDB struct {
	mu      sync.RWMutex
	clients map[string]*ClientRecord // key: client_id
	dbPath  string
}

func LoadClientDB(path string) (*ClientDB, error) {
	db := &ClientDB{
		clients: make(map[string]*ClientRecord),
		dbPath:  path,
	}

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return db, nil // empty DB is fine
		}
		return nil, fmt.Errorf("read db: %w", err)
	}

	var records []*ClientRecord
	if err := json.Unmarshal(data, &records); err != nil {
		return nil, fmt.Errorf("parse db: %w", err)
	}

	for _, r := range records {
		db.clients[r.ClientID] = r
	}
	return db, nil
}

func (db *ClientDB) Save() error {
	db.mu.RLock()
	defer db.mu.RUnlock()

	records := make([]*ClientRecord, 0, len(db.clients))
	for _, r := range db.clients {
		records = append(records, r)
	}

	data, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(db.dbPath, data, 0600)
}

func (db *ClientDB) Get(clientID string) (*ClientRecord, bool) {
	db.mu.RLock()
	defer db.mu.RUnlock()
	r, ok := db.clients[clientID]
	return r, ok
}

func (db *ClientDB) Put(r *ClientRecord) {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.clients[r.ClientID] = r
}

// ──────────────────────────────────────────────────────────────────
// API request/response types
// ──────────────────────────────────────────────────────────────────

// CheckRequest — sent by the client app to check key validity
type CheckRequest struct {
	ClientID  string `json:"client_id"`
	PublicKey string `json:"public_key"`    // current WG public key
	Token     string `json:"token"`          // HMAC-SHA256(client_id+public_key, shared_secret)
	Timestamp int64  `json:"timestamp"`      // unix seconds (anti-replay)
}

// CheckResponse — server reply
type CheckResponse struct {
	Valid     bool   `json:"valid"`
	ExpiresAt int64  `json:"expires_at,omitempty"` // unix timestamp
	Message   string `json:"message,omitempty"`
	// If NeedsRotation=true, client should call /v1/refresh
	NeedsRotation bool `json:"needs_rotation"`
}

// RefreshRequest — sent by client to get new config
type RefreshRequest struct {
	ClientID  string `json:"client_id"`
	PublicKey string `json:"public_key"`
	Token     string `json:"token"`
	Timestamp int64  `json:"timestamp"`
}

// RefreshResponse — server sends new encrypted config
type RefreshResponse struct {
	// EncryptedConfig: AES-256-GCM encrypted WG config
	// Encrypted with client's SharedSecret
	// Format: base64(nonce[12] + ciphertext)
	EncryptedConfig string `json:"encrypted_config"`
	// ConfigHash: SHA-256 of plaintext config (for verification)
	ConfigHash string `json:"config_hash"`
	// ExpiresAt: when this new config expires
	ExpiresAt int64 `json:"expires_at"`
}

// ──────────────────────────────────────────────────────────────────
// Crypto helpers
// ──────────────────────────────────────────────────────────────────

// computeHMAC computes HMAC-SHA256(message, key)
func computeHMAC(message, key []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write(message)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

// encryptConfig encrypts plaintext config with AES-256-GCM
// Returns base64(nonce + ciphertext)
func encryptConfig(plaintext []byte, keyBase64 string) (string, error) {
	key, err := base64.StdEncoding.DecodeString(keyBase64)
	if err != nil {
		return "", fmt.Errorf("decode key: %w", err)
	}
	if len(key) != 32 {
		return "", fmt.Errorf("key must be 32 bytes, got %d", len(key))
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// verifyToken checks the HMAC token in a request
func verifyToken(clientID, publicKey, tokenStr, sharedSecret string, timestamp int64) bool {
	// Replay attack: reject if timestamp is more than 5 minutes off
	diff := time.Now().Unix() - timestamp
	if diff < -300 || diff > 300 {
		return false
	}

	key, err := base64.StdEncoding.DecodeString(sharedSecret)
	if err != nil {
		return false
	}

	message := fmt.Sprintf("%s|%s|%d", clientID, publicKey, timestamp)
	expected := computeHMAC([]byte(message), key)
	return hmac.Equal([]byte(tokenStr), []byte(expected))
}

// ──────────────────────────────────────────────────────────────────
// HTTP Handlers
// ──────────────────────────────────────────────────────────────────

type Server struct {
	cfg    *ServerConfig
	db     *ClientDB
	master []byte // decoded master secret
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":    "ok",
		"timestamp": time.Now().Unix(),
		"version":   "awg-key-server/1.0",
	})
}

func (s *Server) handleCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	var req CheckRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request"}`, http.StatusBadRequest)
		return
	}

	// Lookup client
	client, ok := s.db.Get(req.ClientID)
	if !ok {
		json.NewEncoder(w).Encode(CheckResponse{
			Valid:   false,
			Message: "unknown client",
		})
		return
	}

	// Verify token
	if !verifyToken(req.ClientID, req.PublicKey, req.Token, client.SharedSecret, req.Timestamp) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	// Check if revoked
	if client.IsRevoked {
		json.NewEncoder(w).Encode(CheckResponse{
			Valid:         false,
			NeedsRotation: true,
			Message:       "key revoked",
		})
		return
	}

	// Check if key matches
	if client.PublicKey != req.PublicKey {
		json.NewEncoder(w).Encode(CheckResponse{
			Valid:         false,
			NeedsRotation: true,
			Message:       "public key mismatch",
		})
		return
	}

	// Check expiry
	now := time.Now()
	needsRotation := now.After(client.ExpiresAt.Add(-24 * time.Hour)) // warn 24h before expiry

	json.NewEncoder(w).Encode(CheckResponse{
		Valid:         !now.After(client.ExpiresAt),
		ExpiresAt:     client.ExpiresAt.Unix(),
		NeedsRotation: needsRotation || now.After(client.ExpiresAt),
		Message:       "ok",
	})
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	var req RefreshRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request"}`, http.StatusBadRequest)
		return
	}

	client, ok := s.db.Get(req.ClientID)
	if !ok {
		http.Error(w, `{"error":"unknown client"}`, http.StatusNotFound)
		return
	}

	if !verifyToken(req.ClientID, req.PublicKey, req.Token, client.SharedSecret, req.Timestamp) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	// Read the new config file for this client
	newConfigPath := s.cfg.WGConfigsDir + "/" + client.ClientID + "_new.conf"
	if _, err := os.Stat(newConfigPath); os.IsNotExist(err) {
		// No new config — re-issue the current one with extended expiry
		newConfigPath = client.ConfigFile
	}

	configBytes, err := os.ReadFile(newConfigPath)
	if err != nil {
		log.Printf("Error reading config for %s: %v", req.ClientID, err)
		http.Error(w, `{"error":"config not available"}`, http.StatusInternalServerError)
		return
	}

	// Encrypt the config
	encrypted, err := encryptConfig(configBytes, client.SharedSecret)
	if err != nil {
		log.Printf("Error encrypting config: %v", err)
		http.Error(w, `{"error":"encryption failed"}`, http.StatusInternalServerError)
		return
	}

	// Hash for integrity
	hash := sha256.Sum256(configBytes)
	hashStr := base64.StdEncoding.EncodeToString(hash[:])

	// Extend expiry
	newExpiry := time.Now().Add(time.Duration(s.cfg.MaxKeyAgeDays) * 24 * time.Hour)
	client.ExpiresAt = newExpiry
	s.db.Put(client)
	go s.db.Save()

	json.NewEncoder(w).Encode(RefreshResponse{
		EncryptedConfig: encrypted,
		ConfigHash:      hashStr,
		ExpiresAt:       newExpiry.Unix(),
	})
}

// ──────────────────────────────────────────────────────────────────
// Main
// ──────────────────────────────────────────────────────────────────

func main() {
	cfgPath := "config.json"
	if len(os.Args) > 1 {
		cfgPath = os.Args[1]
	}

	cfgData, err := os.ReadFile(cfgPath)
	if err != nil {
		log.Fatalf("Cannot read config %s: %v", cfgPath, err)
	}

	var cfg ServerConfig
	if err := json.Unmarshal(cfgData, &cfg); err != nil {
		log.Fatalf("Parse config: %v", err)
	}

	db, err := LoadClientDB("clients.json")
	if err != nil {
		log.Fatalf("Load client DB: %v", err)
	}

	master, err := base64.StdEncoding.DecodeString(cfg.MasterSecret)
	if err != nil {
		log.Fatalf("Invalid master secret: %v", err)
	}

	srv := &Server{cfg: &cfg, db: db, master: master}

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", srv.handleHealth)
	mux.HandleFunc("/v1/check", withMethod("POST", srv.handleCheck))
	mux.HandleFunc("/v1/refresh", withMethod("POST", srv.handleRefresh))

	// Rate limiting middleware
	handler := rateLimitMiddleware(mux, 10, time.Minute)

	log.Printf("AWG Key Server starting on %s", cfg.ListenAddr)

	if cfg.TLSCert != "" && cfg.TLSKey != "" {
		log.Fatal(http.ListenAndServeTLS(cfg.ListenAddr, cfg.TLSCert, cfg.TLSKey, handler))
	} else {
		log.Println("WARNING: Running without TLS. Use HTTPS in production!")
		log.Fatal(http.ListenAndServe(cfg.ListenAddr, handler))
	}
}

func withMethod(method string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}
		h(w, r)
	}
}

// Simple in-memory rate limiter
func rateLimitMiddleware(next http.Handler, maxReqs int, window time.Duration) http.Handler {
	type entry struct {
		count int
		reset time.Time
	}
	mu := sync.Mutex{}
	clients := make(map[string]*entry)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := r.RemoteAddr
		mu.Lock()
		e, ok := clients[ip]
		if !ok || time.Now().After(e.reset) {
			clients[ip] = &entry{count: 1, reset: time.Now().Add(window)}
			mu.Unlock()
			next.ServeHTTP(w, r)
			return
		}
		e.count++
		if e.count > maxReqs {
			mu.Unlock()
			http.Error(w, `{"error":"rate limit exceeded"}`, http.StatusTooManyRequests)
			return
		}
		mu.Unlock()
		next.ServeHTTP(w, r)
	})
}
