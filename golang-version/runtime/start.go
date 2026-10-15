package appruntime

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	_ "time/tzdata"

	admin "github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-admin"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

func Start() error {
	cfg, err := Load()
	if err != nil {
		return err
	}
	store, err := database.Open(cfg.DSN)
	if err != nil {
		return err
	}
	defer store.DB.Close()
	jwt, err := security.NewJWT(cfg.JWTSecret, "tcm-backend-go")
	if err != nil {
		return err
	}
	return serve(newServer(cfg, admin.New(store, jwt, cfg.CORSOrigins)))
}
func newServer(cfg Config, handler http.Handler) *http.Server {
	return &http.Server{Addr: ":" + cfg.Port, Handler: handler, ReadHeaderTimeout: 8 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 90 * time.Second}
}
func serve(server *http.Server) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	done := make(chan error, 1)
	go func() { log.Printf("TCM Go API listening on %s", server.Addr); done <- server.ListenAndServe() }()
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdown)
	}
}
