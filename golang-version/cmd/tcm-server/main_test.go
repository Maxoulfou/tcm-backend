package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestJWTSignAndParse(t *testing.T) {
	a := &app{secret: []byte("test-secret")}
	want := claims{UserID: "42", Name: "Clinician", Email: "doctor@example.test", Roles: []string{"practitioner"}, Exp: time.Now().Add(time.Hour).Unix()}
	token, err := a.signToken(want)
	if err != nil {
		t.Fatal(err)
	}
	got, err := a.parseToken(token)
	if err != nil {
		t.Fatal(err)
	}
	if got.UserID != want.UserID || got.Email != want.Email || !hasRole(got, "practitioner") {
		t.Fatalf("unexpected claims: %#v", got)
	}
}

func TestJWTRejectsTamperingAndExpiry(t *testing.T) {
	a := &app{secret: []byte("test-secret")}
	token, err := a.signToken(claims{UserID: "1", Exp: time.Now().Add(time.Hour).Unix()})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.parseToken(token + "x"); err == nil {
		t.Fatal("tampered token was accepted")
	}
	expired, err := a.signToken(claims{UserID: "1", Exp: time.Now().Add(-time.Minute).Unix()})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.parseToken(expired); err == nil {
		t.Fatal("expired token was accepted")
	}
}

func TestHealthRoute(t *testing.T) {
	a := &app{secret: []byte("test-secret")}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"status": "ok", "service": "tcm-backend-go"})
	})
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	a.middleware(mux).ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d", w.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["status"] != "ok" {
		t.Fatalf("body=%v", body)
	}
}

func TestCamelAndSnakeMapping(t *testing.T) {
	if got := camel("created_at"); got != "createdAt" {
		t.Fatalf("camel = %q", got)
	}
	if got := snake("practitionerId"); got != "practitioner_id" {
		t.Fatalf("snake = %q", got)
	}
}

func TestRoleCompatibility(t *testing.T) {
	c := claims{Roles: []string{"doctor"}}
	if !hasRole(c, "practitioner") {
		t.Fatal("doctor alias should map to practitioner")
	}
	if hasRole(c, "admin") {
		t.Fatal("doctor must not map to admin")
	}
}

func TestFileAccessToken(t *testing.T) {
	a := &app{secret: []byte("test-secret")}
	token := a.fileToken("file-123", time.Now().Add(time.Minute).Unix())
	if id, ok := a.parseFileToken(token); !ok || id != "file-123" {
		t.Fatalf("valid file token rejected: id=%q ok=%v", id, ok)
	}
	if _, ok := a.parseFileToken(token + "x"); ok {
		t.Fatal("tampered file token was accepted")
	}
	expired := a.fileToken("file-123", time.Now().Add(-time.Minute).Unix())
	if _, ok := a.parseFileToken(expired); ok {
		t.Fatal("expired file token was accepted")
	}
}
