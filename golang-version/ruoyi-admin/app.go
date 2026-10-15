package admin

import (
	"net/http"

	system "github.com/Maxoulfou/tcm-backend/golang-version/System"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-generator"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-hospital"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-quartz"
)

func New(store *database.Store, jwt *security.JWT, origins ...string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", health)
	mux.Handle("/api/auth/", &system.Auth{Store: store, JWT: jwt, Limiter: security.NewLimiter()})
	mux.Handle("/api/users", &system.Users{Store: store})
	mux.Handle("/api/users/", &system.Users{Store: store})
	mux.Handle("/api/generator/", &generator.Handler{Store: store})
	mux.Handle("/api/monitor/job", &quartz.Handler{Store: store})
	mux.Handle("/api/", &hospital.Handler{Store: store})
	return security.MaxRequestBody(security.WithSecurity(mux, jwt, origins...), 8<<20)
}
func health(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(200)
	_, _ = w.Write([]byte(`{"status":"ok","service":"tcm-backend-go"}`))
}
