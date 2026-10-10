package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"sync"
	"time"
)

// Session is one signed-in user: an agent, or the operations user who checks invariants.
type Session struct {
	c      *Client
	Phone  string
	UserID string
	cache  *TokenCache

	mu          sync.Mutex
	access      string
	accessUntil time.Time
	refresh     string
}

type tokenAnswer struct {
	AccessToken  string `json:"access_token"`
	ExpiresIn    int    `json:"expires_in"`
	RefreshToken string `json:"refresh_token"`
	User         struct {
		ID    string   `json:"id"`
		Roles []string `json:"roles"`
	} `json:"user"`
}

// SignIn resumes the cached session of a phone, or signs in with a one-time code (the local profile's fixed code).
func (c *Client) SignIn(ctx context.Context, phone, code string, cache *TokenCache) (*Session, error) {
	s := &Session{c: c, Phone: phone, cache: cache}
	if refresh, ok := cache.Get(phone); ok {
		s.refresh = refresh
		if err := s.rotate(ctx); err == nil {
			return s, nil
		}
	}
	if _, err := c.call(ctx, http.MethodPost, "/v1/auth/otp", "", map[string]string{"phone": phone}, nil, nil); err != nil {
		return nil, err
	}
	var answer tokenAnswer
	if _, err := c.call(ctx, http.MethodPost, "/v1/auth/token", "",
		map[string]string{"phone": phone, "code": code}, &answer, nil); err != nil {
		return nil, err
	}
	s.keep(answer)
	return s, nil
}

// rotate exchanges the refresh token; the caller holds mu or owns the session alone.
func (s *Session) rotate(ctx context.Context) error {
	var answer tokenAnswer
	if _, err := s.c.call(ctx, http.MethodPost, "/v1/auth/refresh", "", map[string]string{"refresh_token": s.refresh},
		&answer, nil); err != nil {
		return err
	}
	s.keep(answer)
	return nil
}

func (s *Session) keep(answer tokenAnswer) {
	s.access = answer.AccessToken
	s.accessUntil = time.Now().Add(time.Duration(answer.ExpiresIn) * time.Second)
	s.refresh = answer.RefreshToken
	s.UserID = answer.User.ID
	s.cache.Put(s.Phone, s.refresh)
}

// token answers an access token with at least a minute left, rotating the refresh token when needed. One rotation at
// a time: a rotated refresh token reused later revokes the session.
func (s *Session) token(ctx context.Context, force bool) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if force || time.Until(s.accessUntil) < time.Minute {
		if err := s.rotate(ctx); err != nil {
			return "", err
		}
	}
	return s.access, nil
}

// Do calls an endpoint as this user; key is the Idempotency-Key, "" for reads.
func (s *Session) Do(ctx context.Context, method, path, key string, body, out any) (int, error) {
	return s.c.call(ctx, method, path, key, body, out, s.token)
}

// TokenCache keeps refresh tokens between runs (they last 30 days), so a rerun signs 2,500 agents in without codes.
type TokenCache struct {
	path   string
	mu     sync.Mutex
	tokens map[string]string
}

// LoadTokenCache reads the cache file; a missing file, or path "", is an empty cache.
func LoadTokenCache(path string) (*TokenCache, error) {
	cache := &TokenCache{path: path, tokens: map[string]string{}}
	if path == "" {
		return cache, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return cache, nil
	}
	if err != nil {
		return nil, err
	}
	return cache, json.Unmarshal(data, &cache.tokens)
}

func (t *TokenCache) Get(phone string) (string, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	token, ok := t.tokens[phone]
	return token, ok
}

func (t *TokenCache) Put(phone, token string) {
	t.mu.Lock()
	t.tokens[phone] = token
	t.mu.Unlock()
}

// Save writes the cache, readable by its owner only: refresh tokens are credentials.
func (t *TokenCache) Save() error {
	if t.path == "" {
		return nil
	}
	t.mu.Lock()
	data, err := json.Marshal(t.tokens)
	t.mu.Unlock()
	if err != nil {
		return err
	}
	return os.WriteFile(t.path, data, 0o600)
}
