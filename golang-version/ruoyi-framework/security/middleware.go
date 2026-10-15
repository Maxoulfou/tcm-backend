package security

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
)

type contextKey string

const identityKey contextKey = "identity"

type Limiter struct {
	mu   sync.Mutex
	hits map[string]window
}
type window struct {
	count int
	until time.Time
}

func WithSecurity(next http.Handler, jwt *JWT, origins ...string) http.Handler {
	allowlist := env("CORS_ORIGINS", "http://localhost:5173")
	if len(origins) > 0 && origins[0] != "" {
		allowlist = origins[0]
	}
	return securityHeaders(recoverer(cors(auth(next, jwt), allowlist)))
}
func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if value := recover(); value != nil {
				log.Printf("request panic: %v", value)
				httpx.Error(w, r, 500, "err.internal")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
func IdentityFrom(ctx context.Context) (Identity, bool) {
	id, ok := ctx.Value(identityKey).(Identity)
	return id, ok
}
func auth(next http.Handler, jwt *JWT) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if publicPath(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		h := r.Header.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			httpx.Error(w, r, 401, "err.unauthorized")
			return
		}
		id, e := jwt.Parse(strings.TrimPrefix(h, "Bearer "))
		if e != nil {
			httpx.Error(w, r, 401, "err.unauthorized")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), identityKey, id)))
	})
}
func publicPath(p string) bool {
	return p == "/health" || p == "/api/auth/login" || p == "/api/public-booking" || strings.HasPrefix(p, "/api/public-booking/") || strings.HasPrefix(p, "/api/consent/") || strings.HasPrefix(p, "/api/intake/") || strings.HasPrefix(p, "/api/public/files/")
}
func cors(next http.Handler, allowlist string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && !allowedOrigin(origin, allowlist) {
			httpx.Error(w, r, 403, "err.forbidden")
			return
		}
		if origin != "" {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Add("Vary", "Origin")
		}
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept-Language")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func allowedOrigin(origin, allowlist string) bool {
	for _, x := range strings.Split(allowlist, ",") {
		if strings.TrimSpace(x) == origin {
			return true
		}
	}
	return false
}
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		next.ServeHTTP(w, r)
	})
}
func MaxRequestBody(next http.Handler, limit int64) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, limit)
		next.ServeHTTP(w, r)
	})
}
func env(key, fallback string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v != "" {
		return v
	}
	return fallback
}
func NewLimiter() *Limiter { return &Limiter{hits: map[string]window{}} }
func (l *Limiter) Allow(r *http.Request, max int, period time.Duration) bool {
	ip, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		ip = r.RemoteAddr
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	w := l.hits[ip]
	if now.After(w.until) {
		w = window{until: now.Add(period)}
	}
	w.count++
	l.hits[ip] = w
	return w.count <= max
}
