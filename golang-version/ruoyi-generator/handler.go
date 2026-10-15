package generator

import (
	"net/http"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

type Handler struct{ Store *database.Store }

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok || !role(id, "admin") {
		httpx.Error(w, r, 403, "err.forbidden")
		return
	}
	if r.Method != "GET" {
		httpx.MethodNotAllowed(w, r)
		return
	}
	rows, e := h.Store.Rows(r.Context(), "SELECT table_name AS tableName,table_comment AS tableComment FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name")
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, rows)
}
func role(id security.Identity, want string) bool {
	for _, r := range id.Roles {
		if r == want {
			return true
		}
	}
	return false
}
