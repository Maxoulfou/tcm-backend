package system

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/i18n"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
	"golang.org/x/crypto/bcrypt"
)

type Auth struct {
	Store   *database.Store
	JWT     *security.JWT
	Limiter *security.Limiter
}
type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}
type account struct {
	ID                                     int64
	Name, Email, Password, Status, Deleted string
}
type passwordChange struct {
	Old string `json:"oldPassword"`
	New string `json:"newPassword"`
}

func (a *Auth) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	route := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/auth/"), "/")
	switch route {
	case "login":
		a.method(w, r, "POST", a.login)
	case "me":
		a.method(w, r, "GET", a.me)
	case "change-password":
		a.method(w, r, "POST", a.changePassword)
	case "reset-password":
		a.method(w, r, "POST", a.resetPassword)
	default:
		httpx.Error(w, r, 404, "err.not_found")
	}
}
func (a *Auth) method(w http.ResponseWriter, r *http.Request, want string, fn http.HandlerFunc) {
	if r.Method != want {
		httpx.MethodNotAllowed(w, r)
		return
	}
	fn(w, r)
}
func (a *Auth) login(w http.ResponseWriter, r *http.Request) {
	if !a.Limiter.Allow(r, 8, time.Minute) {
		httpx.Error(w, r, 429, "err.rate_limited")
		return
	}
	in, e := decodeCredentials(r)
	if e != nil {
		httpx.Error(w, r, 400, "err.invalid_json")
		return
	}
	u, e := a.authenticate(in)
	if e != nil {
		httpx.Error(w, r, 401, "auth.bad_credentials")
		return
	}
	a.writeSession(w, r, u)
}
func (a *Auth) authenticate(in credentials) (account, error) {
	u, e := a.findUser(in.Email)
	if e != nil || u.Status != "0" || u.Deleted != "0" {
		return account{}, http.ErrNoCookie
	}
	if bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(in.Password)) != nil {
		return account{}, http.ErrNoCookie
	}
	return u, nil
}
func (a *Auth) writeSession(w http.ResponseWriter, r *http.Request, u account) {
	roles, e := a.roles(u.ID)
	if e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	token, e := a.JWT.Sign(security.Identity{UserID: strconv.FormatInt(u.ID, 10), Name: u.Name, Email: u.Email, Roles: roles})
	if e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	httpx.JSON(w, 200, map[string]any{"token": token, "user": map[string]any{"id": u.ID, "name": u.Name, "email": u.Email, "roles": roles}})
}
func decodeCredentials(r *http.Request) (credentials, error) {
	var in credentials
	e := json.NewDecoder(r.Body).Decode(&in)
	return in, e
}
func (a *Auth) findUser(email string) (account, error) {
	var u account
	e := a.Store.DB.QueryRow("SELECT user_id,nick_name,email,password,status,del_flag FROM sys_user WHERE email=? OR user_name=? LIMIT 1", email, email).Scan(&u.ID, &u.Name, &u.Email, &u.Password, &u.Status, &u.Deleted)
	return u, e
}
func (a *Auth) roles(id int64) ([]string, error) {
	rows, e := a.Store.DB.Query("SELECT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0'", id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	roles := []string{}
	for rows.Next() {
		var role string
		if e = rows.Scan(&role); e != nil {
			return nil, e
		}
		roles = append(roles, role)
	}
	return roles, rows.Err()
}
func (a *Auth) me(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok {
		httpx.Error(w, r, 401, "err.unauthorized")
		return
	}
	httpx.JSON(w, 200, map[string]any{"user": map[string]any{"id": id.UserID, "name": id.Name, "email": id.Email, "roles": id.Roles}})
}
func (a *Auth) changePassword(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok {
		httpx.Error(w, r, 401, "err.unauthorized")
		return
	}
	var in passwordChange
	if json.NewDecoder(r.Body).Decode(&in) != nil || in.Old == "" || in.New == "" {
		httpx.Error(w, r, 400, "auth.password_required")
		return
	}
	if len(in.New) < 8 {
		httpx.Error(w, r, 400, "auth.password_short")
		return
	}
	a.updatePassword(w, r, id.UserID, in.Old, in.New)
}
func (a *Auth) updatePassword(w http.ResponseWriter, r *http.Request, userID, old, new string) {
	var hash string
	e := a.Store.DB.QueryRow("SELECT password FROM sys_user WHERE user_id=? AND del_flag='0'", userID).Scan(&hash)
	if e != nil || bcrypt.CompareHashAndPassword([]byte(hash), []byte(old)) != nil {
		httpx.Error(w, r, 400, "auth.password_old_wrong")
		return
	}
	encoded, e := bcrypt.GenerateFromPassword([]byte(new), bcrypt.DefaultCost)
	if e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	if _, e = a.Store.DB.Exec("UPDATE sys_user SET password=? WHERE user_id=?", encoded, userID); e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	a.passwordChanged(w, r)
}
func (a *Auth) passwordChanged(w http.ResponseWriter, r *http.Request) {
	text := i18n.Text(i18n.FromHeader(r.Header.Get("Accept-Language")), "auth.password_changed")
	httpx.JSON(w, 200, map[string]string{"message": text})
}
func (a *Auth) resetPassword(w http.ResponseWriter, r *http.Request) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok || !hasRole(id, "admin") {
		httpx.Error(w, r, 403, "err.forbidden")
		return
	}
	in, e := decodeReset(r)
	if e != nil || in.UserID == "" {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	if len(in.Password) < 8 {
		httpx.Error(w, r, 400, "auth.password_short")
		return
	}
	a.saveReset(w, r, in)
}
func decodeReset(r *http.Request) (resetRequest, error) {
	var in resetRequest
	e := json.NewDecoder(r.Body).Decode(&in)
	return in, e
}

type resetRequest struct {
	UserID   string `json:"userId"`
	Password string `json:"newPassword"`
}

func (a *Auth) saveReset(w http.ResponseWriter, r *http.Request, in resetRequest) {
	hash, e := bcrypt.GenerateFromPassword([]byte(in.Password), bcrypt.DefaultCost)
	if e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	if _, e = a.Store.DB.Exec("UPDATE sys_user SET password=? WHERE user_id=?", hash, in.UserID); e != nil {
		httpx.Error(w, r, 500, "err.internal")
		return
	}
	httpx.JSON(w, 200, map[string]bool{"success": true})
}
func hasRole(id security.Identity, want string) bool {
	for _, role := range id.Roles {
		if role == want || (role == "doctor" && want == "practitioner") {
			return true
		}
	}
	return false
}
