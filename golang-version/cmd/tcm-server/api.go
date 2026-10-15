package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func (a *app) api(w http.ResponseWriter, r *http.Request) {
	if a.db == nil {
		writeError(w, 503, "database is not configured")
		return
	}
	p := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/"), "/")
	parts := strings.Split(p, "/")
	if p == "" {
		writeError(w, 404, "not found")
		return
	}
	if parts[0] == "auth" {
		a.auth(w, r, parts[1:])
		return
	}
	if parts[0] == "public-booking" {
		a.publicBooking(w, r, parts[1:])
		return
	}
	if parts[0] == "consent" {
		a.consent(w, r, parts[1:])
		return
	}
	if parts[0] == "intake" {
		a.intake(w, r, parts[1:])
		return
	}
	if parts[0] == "public" {
		a.publicFile(w, r, parts[1:])
		return
	}
	c, ok := a.authenticate(w, r)
	if !ok {
		return
	}
	route := parts[0]
	tail := parts[1:]
	if !a.authorize(w, c, route, r.Method, tail) {
		return
	}
	if route == "settings" && mutating(r.Method) && !hasRole(c, "admin") {
		writeError(w, 403, "administrator role required")
		return
	}
	if route == "settings" {
		a.settings(w, r, tail)
		return
	}
	if route == "statistics" {
		a.statistics(w, tail)
		return
	}
	if route == "audit-logs" {
		a.auditLogs(w)
		return
	}
	if route == "stripe" || route == "ai" || (route == "inventory" && len(tail) > 0 && tail[0] == "invoices") {
		writeError(w, 501, "external integration is not configured in the Go edition")
		return
	}
	if route == "users" {
		a.users(w, r, tail)
		return
	}
	if route == "files" {
		a.filesAPI(w, r, tail)
		return
	}
	res, exists := resources[route]
	if !exists {
		writeError(w, 404, "unknown API route")
		return
	}
	if route == "appointments" && len(tail) > 0 {
		switch tail[0] {
		case "availability", "schedule":
			a.availability(w, r, tail[0])
			return
		case "check-slot":
			a.checkSlot(w, r)
			return
		}
	}
	if route == "appointments" && len(tail) == 2 && tail[1] == "intake-link" && r.Method == "POST" {
		a.appointmentIntakeLink(w, tail[0])
		return
	}
	if route == "patients" && len(tail) == 3 && tail[2] == "send" && (tail[1] == "consent" || tail[1] == "intake") {
		a.patientTokenLink(w, r, tail[0], tail[1])
		return
	}
	if route == "patients" && len(tail) == 2 && tail[1] == "consent" && r.Method == "PATCH" {
		a.signPatientConsent(w, r, tail[0])
		return
	}
	if route == "inventory" && len(tail) > 0 {
		switch tail[0] {
		case "adjustment-history":
			a.adjustmentHistory(w)
			return
		case "by-herb":
			a.byHerb(w, tail[1:])
			return
		case "deduct-prescription", "restore-prescription":
			writeError(w, 501, "prescription stock synchronization is not yet ported")
			return
		case "batch-import":
			writeError(w, 501, "inventory batch import is not yet ported")
			return
		}
	}
	a.resourceAPI(w, r, res, tail)
}
func mutating(m string) bool { return m == "POST" || m == "PUT" || m == "PATCH" || m == "DELETE" }
func (a *app) authorize(w http.ResponseWriter, c claims, route, method string, tail []string) bool {
	allowed := []string{"admin"}
	switch route {
	case "patients", "appointments", "consultations":
		allowed = []string{"admin", "practitioner", "apprentice"}
	case "inventory", "herb-dict", "acupoints", "meridians", "formulas", "unit-conversions":
		allowed = []string{"admin", "practitioner", "pharmacist"}
	case "files":
		allowed = []string{"admin", "practitioner", "apprentice"}
	case "audit-logs", "email-logs", "statistics", "branches", "suppliers", "templates", "settings", "users", "stripe", "ai":
		allowed = []string{"admin", "practitioner"}
	}
	if len(tail) > 0 && mutating(method) {
		switch route {
		case "patients", "appointments", "consultations":
			if !hasAnyRole(c, "admin", "practitioner") {
				writeError(w, 403, "forbidden")
				return false
			}
		case "inventory":
			if !hasRole(c, "admin") && !(method == "POST" && tail[0] == "deduct-prescription") {
				writeError(w, 403, "forbidden")
				return false
			}
		}
	}
	if !hasAnyRole(c, allowed...) {
		writeError(w, 403, "forbidden")
		return false
	}
	return true
}
func (a *app) authenticate(w http.ResponseWriter, r *http.Request) (claims, bool) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		writeError(w, 401, "authentication required")
		return claims{}, false
	}
	c, e := a.parseToken(strings.TrimPrefix(h, "Bearer "))
	if e != nil {
		writeError(w, 401, "invalid or expired token")
		return claims{}, false
	}
	return c, true
}
func hasRole(c claims, w string) bool { return hasAnyRole(c, w) }
func hasAnyRole(c claims, wants ...string) bool {
	for _, r := range c.Roles {
		for _, w := range wants {
			if strings.EqualFold(r, w) || (r == "doctor" && w == "practitioner") {
				return true
			}
		}
	}
	return false
}

func (a *app) auth(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 1 && tail[0] == "login" && r.Method == "POST" {
		a.login(w, r)
		return
	}
	if len(tail) == 1 && tail[0] == "me" && r.Method == "GET" {
		c, ok := a.authenticate(w, r)
		if !ok {
			return
		}
		u, e := a.userView(c.UserID)
		if e != nil {
			writeError(w, 404, "user not found")
			return
		}
		writeJSON(w, 200, map[string]any{"user": u})
		return
	}
	c, ok := a.authenticate(w, r)
	if !ok {
		return
	}
	if len(tail) == 1 && tail[0] == "change-password" && r.Method == "POST" {
		var in struct {
			Old string `json:"oldPassword"`
			New string `json:"newPassword"`
		}
		if decode(r, &in) != nil || in.Old == "" || len(in.New) < 8 {
			writeError(w, 400, "oldPassword and a new password of at least 8 characters are required")
			return
		}
		var old string
		if e := a.db.QueryRow("SELECT password FROM sys_user WHERE user_id=? AND del_flag='0'", c.UserID).Scan(&old); e != nil || bcrypt.CompareHashAndPassword([]byte(old), []byte(in.Old)) != nil {
			writeError(w, 400, "old password is incorrect")
			return
		}
		h, e := bcrypt.GenerateFromPassword([]byte(in.New), bcrypt.DefaultCost)
		if e != nil {
			writeError(w, 500, "password hashing failed")
			return
		}
		_, e = a.db.Exec("UPDATE sys_user SET password=?,pwd_update_date=NOW() WHERE user_id=?", string(h), c.UserID)
		if e != nil {
			writeError(w, 500, "password update failed")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	if len(tail) == 1 && tail[0] == "reset-password" && r.Method == "POST" && hasRole(c, "admin") {
		var in struct {
			ID  string `json:"userId"`
			New string `json:"newPassword"`
		}
		if decode(r, &in) != nil || in.ID == "" || len(in.New) < 8 {
			writeError(w, 400, "userId and a new password of at least 8 characters are required")
			return
		}
		h, e := bcrypt.GenerateFromPassword([]byte(in.New), bcrypt.DefaultCost)
		if e != nil {
			writeError(w, 500, "password hashing failed")
			return
		}
		_, e = a.db.Exec("UPDATE sys_user SET password=?,pwd_update_date=NOW() WHERE user_id=?", string(h), in.ID)
		if e != nil {
			writeError(w, 500, "password reset failed")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	writeError(w, 404, "unknown auth route")
}
func (a *app) login(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if decode(r, &in) != nil || in.Email == "" || in.Password == "" {
		writeError(w, 400, "email and password are required")
		return
	}
	var id int64
	var username, nick, email, pw, status, del string
	e := a.db.QueryRow("SELECT user_id,user_name,nick_name,email,password,status,del_flag FROM sys_user WHERE email=? OR user_name=? LIMIT 1", in.Email, in.Email).Scan(&id, &username, &nick, &email, &pw, &status, &del)
	if e != nil || status != "0" || del != "0" || bcrypt.CompareHashAndPassword([]byte(pw), []byte(in.Password)) != nil {
		writeError(w, 401, "email or password is incorrect")
		return
	}
	roles := []string{}
	rows, e := a.db.Query("SELECT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=? AND r.status='0'", id)
	if e == nil {
		defer rows.Close()
		for rows.Next() {
			var x string
			if rows.Scan(&x) == nil {
				if x == "doctor" {
					x = "practitioner"
				}
				roles = append(roles, x)
			}
		}
	}
	if len(roles) == 0 {
		roles = []string{"admin"}
	}
	c := claims{UserID: strconv.FormatInt(id, 10), Name: nick, Email: email, Roles: roles, Exp: time.Now().Add(12 * time.Hour).Unix()}
	token, e := a.signToken(c)
	if e != nil {
		writeError(w, 500, "token creation failed")
		return
	}
	writeJSON(w, 200, map[string]any{"token": token, "user": map[string]any{"id": c.UserID, "name": nick, "email": email, "phone": "", "role": roles[0], "roles": roles, "isActive": true}})
}
func (a *app) userView(id string) (map[string]any, error) {
	var name, email, phone string
	e := a.db.QueryRow("SELECT nick_name,email,phonenumber FROM sys_user WHERE user_id=? AND del_flag='0'", id).Scan(&name, &email, &phone)
	if e != nil {
		return nil, e
	}
	return map[string]any{"id": id, "name": name, "email": email, "phone": phone}, nil
}

func (a *app) resourceAPI(w http.ResponseWriter, r *http.Request, res resource, tail []string) {
	cols, e := a.columns(res.table)
	if e != nil || len(cols) == 0 {
		writeError(w, 500, "resource table/schema unavailable")
		return
	}
	if len(tail) == 0 {
		switch r.Method {
		case "GET":
			a.listResource(w, r, res, cols)
		case "POST":
			a.createResource(w, r, res, cols)
		default:
			methodNotAllowed(w)
		}
		return
	}
	id := tail[0]
	if len(tail) > 1 {
		if len(tail) == 2 && tail[1] == "adjust" && res.table == "tcm_inventory_item" && r.Method == "POST" {
			a.adjustInventory(w, r, id)
			return
		}
		if len(tail) == 2 && tail[1] == "merge" && res.table == "tcm_patient" && r.Method == "POST" {
			a.mergePatients(w, r, id)
			return
		}
		if len(tail) == 2 && tail[1] == "payments" && res.table == "tcm_consultation" && r.Method == "POST" {
			a.recordPayment(w, r, id)
			return
		}
		if len(tail) == 2 && (tail[1] == "status" || tail[1] == "complete" || tail[1] == "reactivate" || tail[1] == "paid" || tail[1] == "dispense" || tail[1] == "delete" || tail[1] == "restore") {
			a.transition(w, r, res, id, tail[1], cols)
			return
		}
		writeError(w, 501, "this action is not implemented in the Go edition")
		return
	}
	switch r.Method {
	case "GET":
		row, e := a.one(res, id)
		if e != nil {
			writeError(w, 404, "record not found")
		} else {
			writeJSON(w, 200, normalizeRow(row))
		}
	case "PUT", "PATCH":
		a.updateResource(w, r, res, id, cols)
	case "DELETE":
		a.deleteResource(w, res, id, cols)
	default:
		methodNotAllowed(w)
	}
}
func (a *app) listResource(w http.ResponseWriter, r *http.Request, res resource, cols map[string]bool) {
	n := 100
	if x, e := strconv.Atoi(r.URL.Query().Get("limit")); e == nil && x > 0 {
		n = min(x, 500)
	}
	off := 0
	if x, e := strconv.Atoi(r.URL.Query().Get("offset")); e == nil && x > 0 {
		off = x
	}
	order := res.pk
	if cols["update_time"] {
		order = "update_time"
	} else if cols["create_time"] {
		order = "create_time"
	}
	q := "SELECT * FROM " + qi(res.table)
	if cols["deleted_at"] && r.URL.Query().Get("includeDeleted") != "true" {
		q += " WHERE deleted_at IS NULL"
	}
	q += " ORDER BY " + qi(order) + " DESC LIMIT ? OFFSET ?"
	rows, e := a.db.Query(q, n, off)
	if e != nil {
		writeError(w, 500, "query failed")
		return
	}
	defer rows.Close()
	out, e := scanRows(rows)
	if e != nil {
		writeError(w, 500, "result decode failed")
		return
	}
	for i := range out {
		out[i] = normalizeRow(out[i])
	}
	writeJSON(w, 200, out)
}
func (a *app) createResource(w http.ResponseWriter, r *http.Request, res resource, cols map[string]bool) {
	var body map[string]any
	if decode(r, &body) != nil {
		writeError(w, 400, "invalid JSON body")
		return
	}
	f := mapInput(body, cols)
	if cols[res.pk] && empty(f[res.pk]) && res.pk != "service_key" {
		f[res.pk] = randomID()
	}
	if cols["create_time"] && f["create_time"] == nil {
		f["create_time"] = time.Now()
	}
	if cols["update_time"] && f["update_time"] == nil {
		f["update_time"] = time.Now()
	}
	if res.table == "tcm_appointment" && empty(f["status"]) {
		f["status"] = "booked"
	}
	if res.table == "tcm_consultation" && empty(f["status"]) {
		f["status"] = "draft"
	}
	if e := a.insert(res.table, f); e != nil {
		writeError(w, 400, "insert failed; check required fields and uniqueness")
		return
	}
	id := f[res.pk]
	if id == nil {
		id = body[camel(res.pk)]
	}
	row, e := a.one(res, fmt.Sprint(id))
	if e != nil {
		writeJSON(w, 201, map[string]any{"success": true})
		return
	}
	writeJSON(w, 201, normalizeRow(row))
}
func (a *app) updateResource(w http.ResponseWriter, r *http.Request, res resource, id string, cols map[string]bool) {
	var body map[string]any
	if decode(r, &body) != nil {
		writeError(w, 400, "invalid JSON body")
		return
	}
	f := mapInput(body, cols)
	delete(f, res.pk)
	if cols["update_time"] {
		f["update_time"] = time.Now()
	}
	if len(f) == 0 {
		writeError(w, 400, "no recognized fields")
		return
	}
	if e := a.update(res, id, f); e != nil {
		writeError(w, 400, "update failed")
		return
	}
	row, e := a.one(res, id)
	if e != nil {
		writeError(w, 404, "record not found")
	} else {
		writeJSON(w, 200, normalizeRow(row))
	}
}
func (a *app) deleteResource(w http.ResponseWriter, res resource, id string, cols map[string]bool) {
	var e error
	if cols["deleted_at"] {
		_, e = a.db.Exec("UPDATE "+qi(res.table)+" SET deleted_at=NOW() WHERE "+qi(res.pk)+"=?", id)
	} else {
		_, e = a.db.Exec("DELETE FROM "+qi(res.table)+" WHERE "+qi(res.pk)+"=?", id)
	}
	if e != nil {
		writeError(w, 400, "delete failed")
	} else {
		writeJSON(w, 200, map[string]any{"success": true})
	}
}
func (a *app) transition(w http.ResponseWriter, r *http.Request, res resource, id, action string, cols map[string]bool) {
	if r.Method == "DELETE" {
		a.deleteResource(w, res, id, cols)
		return
	}
	if r.Method != "PATCH" && r.Method != "POST" {
		methodNotAllowed(w)
		return
	}
	f := map[string]any{}
	switch action {
	case "complete":
		f["status"] = "completed"
	case "reactivate":
		f["status"] = "in_progress"
	case "paid":
		f["payment_status"] = "paid"
	case "dispense":
		f["dispensing_status"] = "completed"
	case "status":
		var b map[string]any
		if decode(r, &b) != nil {
			writeError(w, 400, "invalid JSON")
			return
		}
		f = mapInput(b, cols)
	case "delete":
		if cols["deleted_at"] {
			f["deleted_at"] = time.Now()
		} else {
			a.deleteResource(w, res, id, cols)
			return
		}
	case "restore":
		f["deleted_at"] = nil
	}
	for k := range f {
		if !cols[k] {
			delete(f, k)
		}
	}
	if cols["update_time"] {
		f["update_time"] = time.Now()
	}
	if len(f) == 0 {
		writeError(w, 501, "transition is not supported for this table")
		return
	}
	if e := a.update(res, id, f); e != nil {
		writeError(w, 400, "transition failed")
		return
	}
	row, e := a.one(res, id)
	if e != nil {
		writeError(w, 404, "record not found")
	} else {
		writeJSON(w, 200, normalizeRow(row))
	}
}

func (a *app) adjustInventory(w http.ResponseWriter, r *http.Request, id string) {
	var in struct {
		Delta  json.Number `json:"delta"`
		Reason string      `json:"reason"`
	}
	if decode(r, &in) != nil {
		writeError(w, 400, "invalid JSON")
		return
	}
	d, e := strconv.ParseFloat(string(in.Delta), 64)
	if e != nil || math.IsNaN(d) || math.IsInf(d, 0) {
		writeError(w, 400, "delta must be a valid number")
		return
	}
	_, e = a.db.Exec("UPDATE tcm_inventory_item SET quantity=quantity+?,update_time=NOW() WHERE id=? AND deleted_at IS NULL", d, id)
	if e != nil {
		writeError(w, 400, "stock adjustment failed")
		return
	}
	row, e := a.one(resources["inventory"], id)
	if e != nil {
		writeError(w, 404, "inventory item not found")
	} else {
		writeJSON(w, 200, normalizeRow(row))
	}
}
func (a *app) mergePatients(w http.ResponseWriter, r *http.Request, id string) {
	var in struct {
		MergeID string `json:"mergeId"`
	}
	if decode(r, &in) != nil || in.MergeID == "" || id == in.MergeID {
		writeError(w, 400, "mergeId is required and must differ from id")
		return
	}
	tx, e := a.db.Begin()
	if e != nil {
		writeError(w, 500, "transaction failed")
		return
	}
	defer tx.Rollback()
	for _, t := range []string{"tcm_appointment", "tcm_consultation", "tcm_patient_file"} {
		if _, e = tx.Exec("UPDATE "+qi(t)+" SET patient_id=? WHERE patient_id=?", id, in.MergeID); e != nil {
			writeError(w, 500, "merge failed")
			return
		}
	}
	if _, e = tx.Exec("UPDATE tcm_patient SET deleted_at=NOW(),update_time=NOW() WHERE id=?", in.MergeID); e != nil {
		writeError(w, 500, "merge failed")
		return
	}
	if tx.Commit() != nil {
		writeError(w, 500, "merge failed")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}
func (a *app) recordPayment(w http.ResponseWriter, r *http.Request, id string) {
	var b map[string]any
	if decode(r, &b) != nil {
		writeError(w, 400, "invalid JSON")
		return
	}
	row, e := a.one(resources["consultations"], id)
	if e != nil {
		writeError(w, 404, "consultation not found")
		return
	}
	p := map[string]any{}
	if s, ok := row["payload"].(string); ok {
		_ = json.Unmarshal([]byte(s), &p)
	}
	payments, _ := p["payments"].([]any)
	if payments == nil {
		payments = []any{}
	}
	b["createdAt"] = time.Now().Format(time.RFC3339)
	p["payments"] = append(payments, b)
	raw, _ := json.Marshal(p)
	_, e = a.db.Exec("UPDATE tcm_consultation SET payload=?,update_time=NOW() WHERE id=?", string(raw), id)
	if e != nil {
		writeError(w, 500, "payment save failed")
		return
	}
	row, _ = a.one(resources["consultations"], id)
	writeJSON(w, 200, normalizeRow(row))
}
func (a *app) appointmentIntakeLink(w http.ResponseWriter, id string) {
	row, e := a.one(resources["appointments"], id)
	if e != nil {
		writeError(w, 404, "appointment not found")
		return
	}
	p := payload(row)
	tok := randomID()
	p["intakeToken"] = strings.ReplaceAll(tok, "-", "")
	p["intakeSubmitted"] = 0
	raw, _ := json.Marshal(p)
	_, e = a.db.Exec("UPDATE tcm_appointment SET payload=?,update_time=NOW() WHERE id=?", string(raw), id)
	if e != nil {
		writeError(w, 500, "could not save intake link")
		return
	}
	writeJSON(w, 200, map[string]any{"token": p["intakeToken"]})
}
func (a *app) checkSlot(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Practitioner string `json:"practitionerId"`
		Room         string `json:"roomId"`
		Service      string `json:"serviceType"`
		Start        string `json:"startTime"`
		End          string `json:"endTime"`
		Exclude      string `json:"excludeId"`
	}
	if decode(r, &in) != nil || in.Start == "" || in.End == "" {
		writeError(w, 400, "startTime and endTime are required")
		return
	}
	start, e := parseDateTime(in.Start, a.loc)
	if e != nil {
		writeError(w, 400, "invalid startTime")
		return
	}
	end, e := parseDateTime(in.End, a.loc)
	if e != nil || !end.After(start) {
		writeError(w, 400, "invalid endTime")
		return
	}
	rows, e := a.db.Query("SELECT id,practitioner_id,room_id FROM tcm_appointment WHERE status NOT IN ('cancelled','canceled') AND start_time < ? AND end_time > ?", end, start)
	if e != nil {
		writeError(w, 500, "could not check slot")
		return
	}
	defer rows.Close()
	conflicts := []string{}
	for rows.Next() {
		var id, p, room sql.NullString
		if rows.Scan(&id, &p, &room) != nil {
			continue
		}
		if id.String == in.Exclude {
			continue
		}
		if (in.Practitioner != "" && p.String == in.Practitioner) || (in.Room != "" && room.String == in.Room) {
			conflicts = append(conflicts, id.String)
		}
	}
	writeJSON(w, 200, map[string]any{"available": len(conflicts) == 0, "conflicts": conflicts})
}
func (a *app) availability(w http.ResponseWriter, r *http.Request, kind string) {
	date, service := r.URL.Query().Get("date"), r.URL.Query().Get("serviceType")
	if date == "" || service == "" {
		writeError(w, 400, "date and serviceType are required")
		return
	}
	day, e := time.Parse("2006-01-02", date)
	if e != nil {
		writeError(w, 400, "date must be YYYY-MM-DD")
		return
	}
	duration := 30
	var d sql.NullInt64
	_ = a.db.QueryRow("SELECT duration FROM tcm_service_type WHERE service_key=?", service).Scan(&d)
	if d.Valid && d.Int64 > 0 {
		duration = int(d.Int64)
	}
	start := time.Date(day.Year(), day.Month(), day.Day(), 9, 0, 0, 0, a.loc)
	end := start.Add(9 * time.Hour)
	rows, e := a.db.Query("SELECT start_time,end_time,practitioner_id,room_id FROM tcm_appointment WHERE DATE(start_time)=? AND status NOT IN ('cancelled','canceled')", date)
	if e != nil {
		writeError(w, 500, "could not load appointments")
		return
	}
	defer rows.Close()
	type busy struct {
		s, e    time.Time
		p, room sql.NullString
	}
	var busyRows []busy
	for rows.Next() {
		var b busy
		if rows.Scan(&b.s, &b.e, &b.p, &b.room) == nil {
			busyRows = append(busyRows, b)
		}
	}
	slots := []map[string]any{}
	for t := start; t.Add(time.Duration(duration)*time.Minute).Compare(end) <= 0; t = t.Add(10 * time.Minute) {
		finish := t.Add(time.Duration(duration) * time.Minute)
		free := true
		for _, b := range busyRows {
			if t.Before(b.e) && finish.After(b.s) {
				free = false
				break
			}
		}
		if free {
			slots = append(slots, map[string]any{"startTime": t.Format("2006-01-02 15:04:05"), "endTime": finish.Format("2006-01-02 15:04:05"), "available": true})
		}
	}
	if kind == "availability" {
		writeJSON(w, 200, map[string]any{"date": date, "serviceType": service, "duration": duration, "slotStepMinutes": 10, "slots": slots})
	} else {
		writeJSON(w, 200, map[string]any{"date": date, "serviceType": service, "duration": duration, "slotStepMinutes": 10, "days": []any{map[string]any{"date": date, "slots": slots}}})
	}
}
func (a *app) publicBooking(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 1 && tail[0] == "options" && r.Method == "GET" {
		services, e := a.query("SELECT service_key,label,duration,default_price,required_tag FROM tcm_service_type WHERE public_visible=1")
		if e != nil {
			writeError(w, 500, "booking options unavailable")
			return
		}
		rooms, _ := a.query("SELECT id,name,support_tags FROM tcm_room WHERE is_active=1")
		people, _ := a.query("SELECT u.user_id AS id,u.nick_name AS name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role ro ON ro.role_id=ur.role_id WHERE ro.role_key IN ('doctor','practitioner') AND u.status='0' AND u.del_flag='0'")
		today := time.Now().In(a.loc).Format("2006-01-02")
		writeJSON(w, 200, map[string]any{"serviceTypes": services, "rooms": rooms, "practitioners": people, "publicWindowStart": today, "publicWindowEnd": time.Now().In(a.loc).AddDate(0, 0, 14).Format("2006-01-02")})
		return
	}
	if len(tail) == 1 && (tail[0] == "availability" || tail[0] == "schedule") {
		a.availability(w, r, tail[0])
		return
	}
	if len(tail) == 0 && r.Method == "POST" {
		var b map[string]any
		if decode(r, &b) != nil {
			writeError(w, 400, "invalid JSON")
			return
		}
		name := strings.TrimSpace(stringValue(b["firstName"]) + " " + stringValue(b["lastName"]))
		if name == "" {
			name = stringValue(b["patientName"])
		}
		if name == "" || b["startTime"] == nil || b["serviceType"] == nil {
			writeError(w, 400, "name, startTime and serviceType are required")
			return
		}
		pid, aid := randomID(), randomID()
		pc := mustColumns(a, "tcm_patient")
		pf := filterFields(map[string]any{"id": pid, "name": name, "first_name": b["firstName"], "last_name": b["lastName"], "phone": b["phone"], "email": b["email"], "create_time": time.Now(), "update_time": time.Now()}, pc)
		if e := a.insert("tcm_patient", pf); e != nil {
			writeError(w, 400, "patient creation failed")
			return
		}
		af := filterFields(map[string]any{"id": aid, "patient_id": pid, "practitioner_id": b["practitionerId"], "room_id": b["roomId"], "service_type": b["serviceType"], "start_time": b["startTime"], "end_time": b["endTime"], "status": "booked", "payload": jsonString(map[string]any{"bookingSource": "public"}), "create_time": time.Now(), "update_time": time.Now()}, mustColumns(a, "tcm_appointment"))
		if e := a.insert("tcm_appointment", af); e != nil {
			writeError(w, 400, "appointment creation failed")
			return
		}
		row, _ := a.one(resources["appointments"], aid)
		writeJSON(w, 201, map[string]any{"appointment": normalizeRow(row), "patientId": pid})
		return
	}
	writeError(w, 404, "unknown public booking route")
}

func (a *app) settings(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) > 0 && (tail[0] == "rooms" || tail[0] == "service-types" || tail[0] == "price-lists") {
		res := resources[tail[0]]
		if len(tail) == 1 && r.Method == "POST" {
			a.resourceAPI(w, r, res, nil)
			return
		}
		if len(tail) == 2 && (r.Method == "PUT" || r.Method == "PATCH" || r.Method == "DELETE") {
			a.resourceAPI(w, r, res, tail[1:])
			return
		}
		writeError(w, 404, "unknown settings route")
		return
	}
	if len(tail) > 0 && tail[0] == "stripe" {
		writeError(w, 501, "payment provider is not configured")
		return
	}
	if len(tail) > 0 && (tail[0] == "signature/upload" || tail[0] == "seal/upload") {
		writeError(w, 501, "file storage is not configured")
		return
	}
	switch r.Method {
	case "GET":
		rows, e := a.query("SELECT setting_key,setting_value FROM tcm_clinic_setting")
		if e != nil {
			writeError(w, 500, "settings read failed")
			return
		}
		out := map[string]any{}
		for _, x := range rows {
			out[fmt.Sprint(x["setting_key"])] = x["setting_value"]
		}
		writeJSON(w, 200, out)
	case "PUT":
		if len(tail) > 1 || (len(tail) == 1 && tail[0] != "base") {
			writeError(w, 404, "unknown settings route")
			return
		}
		var b map[string]any
		if decode(r, &b) != nil {
			writeError(w, 400, "invalid JSON")
			return
		}
		tx, e := a.db.Begin()
		if e != nil {
			writeError(w, 500, "settings update failed")
			return
		}
		defer tx.Rollback()
		for k, v := range b {
			if _, e = tx.Exec("INSERT INTO tcm_clinic_setting(setting_key,setting_value) VALUES(?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),update_time=NOW()", k, dbValue(v)); e != nil {
				writeError(w, 500, "settings update failed")
				return
			}
		}
		if tx.Commit() != nil {
			writeError(w, 500, "settings update failed")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true})
	default:
		methodNotAllowed(w)
	}
}
func (a *app) statistics(w http.ResponseWriter, tail []string) {
	if len(tail) != 1 || tail[0] != "overview" {
		writeError(w, 404, "unknown statistics route")
		return
	}
	out := map[string]any{}
	for k, q := range map[string]string{"patients": "SELECT COUNT(*) FROM tcm_patient WHERE deleted_at IS NULL", "appointments": "SELECT COUNT(*) FROM tcm_appointment", "consultations": "SELECT COUNT(*) FROM tcm_consultation WHERE deleted_at IS NULL", "inventoryItems": "SELECT COUNT(*) FROM tcm_inventory_item WHERE deleted_at IS NULL"} {
		var n int64
		if a.db.QueryRow(q).Scan(&n) == nil {
			out[k] = n
		}
	}
	writeJSON(w, 200, out)
}
func (a *app) auditLogs(w http.ResponseWriter) {
	rows, e := a.query("SELECT id,consultation_id AS target_id,mod_date AS created_at,mod_type AS entity_type,action,user_id,changes FROM tcm_consultation_mod ORDER BY mod_date DESC LIMIT 100")
	if e != nil {
		writeError(w, 500, "audit log read failed")
	} else {
		writeJSON(w, 200, rows)
	}
}
func (a *app) adjustmentHistory(w http.ResponseWriter) {
	rows, e := a.query("SELECT mod_date AS created_at,action,consultation_id AS target_id,user_id,changes FROM tcm_consultation_mod WHERE mod_type='inventory' ORDER BY mod_date DESC LIMIT 500")
	if e != nil {
		writeError(w, 500, "history read failed")
	} else {
		writeJSON(w, 200, rows)
	}
}
func (a *app) byHerb(w http.ResponseWriter, tail []string) {
	if len(tail) != 1 {
		writeError(w, 404, "herb id required")
		return
	}
	rows, e := a.db.Query("SELECT * FROM tcm_inventory_item WHERE JSON_EXTRACT(payload,'$.herbDictId')=? OR name IN (SELECT name FROM tcm_herb_dict WHERE id=?)", tail[0], tail[0])
	if e != nil {
		writeError(w, 500, "inventory lookup failed")
		return
	}
	defer rows.Close()
	out, e := scanRows(rows)
	if e != nil {
		writeError(w, 500, "inventory lookup failed")
		return
	}
	for i := range out {
		out[i] = normalizeRow(out[i])
	}
	writeJSON(w, 200, out)
}
func (a *app) users(w http.ResponseWriter, r *http.Request, tail []string) {
	if !hasRoleRequest(a, r, "admin") {
		writeError(w, 403, "administrator role required")
		return
	}
	if len(tail) == 0 && r.Method == "GET" {
		rows, e := a.query("SELECT user_id AS id,user_name AS userName,nick_name AS name,email,phonenumber AS phone,status FROM sys_user WHERE del_flag='0'")
		if e != nil {
			writeError(w, 500, "user list failed")
		} else {
			writeJSON(w, 200, rows)
		}
		return
	}
	writeError(w, 501, "user administration is not fully ported")
}

func parseDateTime(s string, loc *time.Location) (time.Time, error) {
	for _, layout := range []string{"2006-01-02 15:04:05", time.RFC3339} {
		if t, e := time.ParseInLocation(layout, s, loc); e == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid datetime")
}
func payload(row map[string]any) map[string]any {
	p := map[string]any{}
	if s, ok := row["payload"].(string); ok {
		_ = json.Unmarshal([]byte(s), &p)
	}
	return p
}
func stringValue(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}
func (a *app) query(q string) ([]map[string]any, error) {
	rows, e := a.db.Query(q)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	return scanRows(rows)
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
