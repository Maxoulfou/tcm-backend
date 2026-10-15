package security

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
)

func TestSecurityHeadersAndJSONEscaping(t *testing.T) {
	jwt, _ := NewJWT("01234567890123456789012345678901", "test")
	handler := WithSecurity(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpx.JSON(w, 200, map[string]string{"text": "<script>alert(1)</script>"})
	}), jwt, "https://app.example")
	req := httptest.NewRequest("GET", "/health", nil)
	req.Header.Set("Origin", "https://app.example")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("missing security header")
	}
	if !strings.Contains(rec.Body.String(), `\u003cscript\u003e`) {
		t.Fatal("JSON did not escape HTML")
	}
}
func TestCorsRejectsUnlistedOrigin(t *testing.T) {
	jwt, _ := NewJWT("01234567890123456789012345678901", "test")
	handler := WithSecurity(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}), jwt, "https://app.example")
	req := httptest.NewRequest("GET", "/health", nil)
	req.Header.Set("Origin", "https://evil.example")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("got %d", rec.Code)
	}
}
