package quartz

import (
	"net/http"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

type Handler struct{ Store *database.Store }

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok || !admin(id) {
		httpx.Error(w, r, 403, "err.forbidden")
		return
	}
	if r.Method != "GET" {
		httpx.MethodNotAllowed(w, r)
		return
	}
	h.list(w, r)
}
func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	rows, e := h.Store.Rows(r.Context(), "SELECT job_id AS id,job_name AS name,job_group AS groupName,invoke_target AS invokeTarget,cron_expression AS cronExpression,status FROM sys_job ORDER BY job_id")
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, rows)
}
func admin(id security.Identity) bool {
	for _, role := range id.Roles {
		if role == "admin" {
			return true
		}
	}
	return false
}
