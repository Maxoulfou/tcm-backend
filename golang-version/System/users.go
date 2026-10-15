package system

import (
	"net/http"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

type Users struct{ Store *database.Store }

func (u *Users) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok || !hasRole(id, "admin") {
		httpx.Error(w, r, 403, "err.forbidden")
		return
	}
	if r.Method != "GET" {
		httpx.MethodNotAllowed(w, r)
		return
	}
	u.list(w, r)
}
func (u *Users) list(w http.ResponseWriter, r *http.Request) {
	rows, e := u.Store.Rows(r.Context(), "SELECT user_id AS id,user_name AS userName,nick_name AS name,email,phonenumber AS phone,status FROM sys_user WHERE del_flag='0' ORDER BY user_id DESC LIMIT 500")
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, rows)
}
