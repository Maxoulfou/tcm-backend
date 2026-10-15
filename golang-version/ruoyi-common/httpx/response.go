package httpx

import (
	"encoding/json"
	"net/http"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/i18n"
)

func JSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func Error(w http.ResponseWriter, r *http.Request, status int, key string) {
	locale := i18n.FromHeader(r.Header.Get("Accept-Language"))
	JSON(w, status, map[string]any{"status": status, "message": i18n.Text(locale, key)})
}
func MethodNotAllowed(w http.ResponseWriter, r *http.Request) {
	Error(w, r, http.StatusMethodNotAllowed, "err.method")
}
