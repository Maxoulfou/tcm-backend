package appruntime

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
)

type Config struct{ Port, AppEnv, JWTSecret, DSN, CORSOrigins string }

func Load() (Config, error) {
	c := Config{Port: value("PORT", "8080"), AppEnv: value("APP_ENV", "development"), JWTSecret: os.Getenv("JWT_SECRET"), DSN: os.Getenv("DB_DSN"), CORSOrigins: value("CORS_ORIGINS", "http://localhost:5173")}
	if c.JWTSecret == "" && c.AppEnv != "production" {
		c.JWTSecret = "local-development-only-change-this-secret-32-bytes"
	}
	if len(c.JWTSecret) < 32 {
		return c, fmt.Errorf("JWT_SECRET must contain at least 32 bytes")
	}
	if c.DSN == "" {
		c.DSN = mysqlDSN()
	}
	if c.DSN == "" {
		return c, fmt.Errorf("DB_DSN or DB_HOST is required")
	}
	return c, nil
}
func mysqlDSN() string {
	host := os.Getenv("DB_HOST")
	if host == "" {
		return ""
	}
	port := value("DB_PORT", "3306")
	cfg := mysql.NewConfig()
	cfg.User = value("DB_USER", "root")
	cfg.Passwd = os.Getenv("DB_PASSWORD")
	cfg.Net = "tcp"
	cfg.Addr = host + ":" + port
	cfg.DBName = value("DB_NAME", "tcm")
	cfg.ParseTime = true
	cfg.Loc = clinicLocation()
	cfg.Params = map[string]string{"charset": "utf8mb4"}
	return cfg.FormatDSN()
}
func clinicLocation() *time.Location {
	loc, e := time.LoadLocation("America/Toronto")
	if e != nil {
		return time.FixedZone("America/Toronto", -5*60*60)
	}
	return loc
}
func value(k, d string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return d
}
