package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

type app struct {
	db     *sql.DB
	secret []byte
	loc    *time.Location
}
type claims struct {
	UserID string   `json:"sub"`
	Name   string   `json:"name"`
	Email  string   `json:"email"`
	Roles  []string `json:"roles"`
	Exp    int64    `json:"exp"`
}
type resource struct{ table, pk string }

var resources = map[string]resource{
	"patients": {"tcm_patient", "id"}, "appointments": {"tcm_appointment", "id"}, "consultations": {"tcm_consultation", "id"},
	"branches": {"tcm_branch", "id"}, "formulas": {"tcm_formula", "id"}, "herb-dict": {"tcm_herb_dict", "id"},
	"inventory": {"tcm_inventory_item", "id"}, "meridians": {"tcm_meridian", "id"}, "acupoints": {"tcm_acupoint", "id"},
	"suppliers": {"tcm_supplier", "id"}, "templates": {"tcm_treatment_template", "id"}, "unit-conversions": {"tcm_unit_conversion", "id"},
	"rooms": {"tcm_room", "id"}, "price-lists": {"tcm_price_list", "id"}, "service-types": {"tcm_service_type", "service_key"},
	"email-logs": {"tcm_email_log", "id"}, "files": {"tcm_patient_file", "id"},
}

func main() {
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		if strings.EqualFold(os.Getenv("APP_ENV"), "production") {
			log.Fatal("JWT_SECRET is required in production")
		}
		secret = "local-development-only-change-this-secret"
		log.Print("WARNING: development JWT secret; set JWT_SECRET before deployment")
	}
	loc, e := time.LoadLocation("America/Toronto")
	if e != nil {
		loc = time.FixedZone("America/Toronto", -5*3600)
	}
	a := &app{secret: []byte(secret), loc: loc}
	if dsn := os.Getenv("DB_DSN"); dsn != "" {
		a.db, e = sql.Open("mysql", dsn)
	} else if host := os.Getenv("DB_HOST"); host != "" {
		dsn := fmt.Sprintf("%s:%s@tcp(%s:%s)/%s?parseTime=true&charset=utf8mb4&loc=America%%2FToronto", env("DB_USER", "root"), os.Getenv("DB_PASSWORD"), host, env("DB_PORT", "3306"), env("DB_NAME", "tcm"))
		a.db, e = sql.Open("mysql", dsn)
	}
	if e != nil {
		log.Fatal(e)
	}
	if a.db != nil {
		a.db.SetMaxOpenConns(30)
		a.db.SetMaxIdleConns(8)
		a.db.SetConnMaxLifetime(5 * time.Minute)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		e = a.db.PingContext(ctx)
		cancel()
		if e != nil {
			log.Printf("database unavailable; DB-backed requests return 503: %v", e)
		}
	} else {
		log.Print("DB_DSN or DB_HOST unset; only /health is available")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"status": "ok", "service": "tcm-backend-go"})
	})
	mux.HandleFunc("/api/", a.api)
	srv := &http.Server{Addr: ":" + env("PORT", "8080"), Handler: a.middleware(mux), ReadHeaderTimeout: 8 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 90 * time.Second}
	log.Printf("TCM Go API listening on %s", srv.Addr)
	log.Fatal(srv.ListenAndServe())
}
func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
func (a *app) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		if o := os.Getenv("CORS_ORIGIN"); o != "" && r.Header.Get("Origin") == o {
			w.Header().Set("Access-Control-Allow-Origin", o)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		}
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
		defer func() {
			if v := recover(); v != nil {
				log.Printf("panic: %v", v)
				writeError(w, 500, "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
