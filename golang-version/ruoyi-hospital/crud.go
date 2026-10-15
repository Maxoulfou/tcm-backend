package hospital

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
)

func (h *Handler) resource(w http.ResponseWriter, r *http.Request, res resource, tail []string) {
	cols, e := h.Store.Columns(r.Context(), res.table)
	if e != nil || len(cols) == 0 {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	if len(tail) == 0 {
		h.collection(w, r, res, cols)
		return
	}
	if len(tail) > 1 {
		h.action(w, r, res, tail, cols)
		return
	}
	h.record(w, r, res, tail[0], cols)
}
func (h *Handler) collection(w http.ResponseWriter, r *http.Request, res resource, cols map[string]bool) {
	switch r.Method {
	case "GET":
		h.list(w, r, res, cols)
	case "POST":
		h.create(w, r, res, cols)
	default:
		httpx.MethodNotAllowed(w, r)
	}
}
func (h *Handler) record(w http.ResponseWriter, r *http.Request, res resource, id string, cols map[string]bool) {
	switch r.Method {
	case "GET":
		h.get(w, r, res, id)
	case "PUT", "PATCH":
		h.update(w, r, res, id, cols)
	case "DELETE":
		h.remove(w, r, res, id, cols)
	default:
		httpx.MethodNotAllowed(w, r)
	}
}
func (h *Handler) list(w http.ResponseWriter, r *http.Request, res resource, cols map[string]bool) {
	rows, e := h.Store.Rows(r.Context(), listQuery(r, res, cols), pageLimit(r), pageOffset(r))
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, normalizeList(rows))
}
func listQuery(r *http.Request, res resource, cols map[string]bool) string {
	table, _ := database.Quote(res.table)
	order := res.pk
	if cols["update_time"] {
		order = "update_time"
	} else if cols["create_time"] {
		order = "create_time"
	}
	column, _ := database.Quote(order)
	query := "SELECT * FROM " + table
	if cols["deleted_at"] && r.URL.Query().Get("includeDeleted") != "true" {
		query += " WHERE deleted_at IS NULL"
	}
	return query + " ORDER BY " + column + " DESC LIMIT ? OFFSET ?"
}
func (h *Handler) get(w http.ResponseWriter, r *http.Request, res resource, id string) {
	row, e := h.Store.One(r.Context(), res.table, res.pk, id)
	if e != nil {
		httpx.Error(w, r, 404, "err.not_found")
		return
	}
	httpx.JSON(w, 200, normalize(row))
}
func (h *Handler) create(w http.ResponseWriter, r *http.Request, res resource, cols map[string]bool) {
	fields, e := createFields(r, res, cols)
	if e != nil {
		httpx.Error(w, r, 400, "err.invalid_json")
		return
	}
	if e = h.Store.Insert(r.Context(), res.table, fields); e != nil {
		httpx.Error(w, r, 400, "err.conflict")
		return
	}
	id := fmt.Sprint(fields[res.pk])
	row, e := h.Store.One(r.Context(), res.table, res.pk, id)
	if e != nil {
		httpx.JSON(w, 201, map[string]bool{"success": true})
		return
	}
	httpx.JSON(w, 201, normalize(row))
}
func createFields(r *http.Request, res resource, cols map[string]bool) (map[string]any, error) {
	body, e := decode(r)
	if e != nil {
		return nil, e
	}
	fields := mapFields(body, cols)
	makeID(fields, res, cols)
	stamp(fields, cols, "create_time")
	stamp(fields, cols, "update_time")
	return fields, nil
}
func (h *Handler) update(w http.ResponseWriter, r *http.Request, res resource, id string, cols map[string]bool) {
	body, e := decode(r)
	if e != nil {
		httpx.Error(w, r, 400, "err.invalid_json")
		return
	}
	fields := mapFields(body, cols)
	delete(fields, res.pk)
	stamp(fields, cols, "update_time")
	if len(fields) == 0 {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	if e = h.Store.Update(r.Context(), res.table, res.pk, id, fields); e != nil {
		httpx.Error(w, r, 400, "err.conflict")
		return
	}
	h.get(w, r, res, id)
}
func (h *Handler) remove(w http.ResponseWriter, r *http.Request, res resource, id string, cols map[string]bool) {
	var e error
	if cols["deleted_at"] {
		e = h.Store.Update(r.Context(), res.table, res.pk, id, map[string]any{"deleted_at": time.Now()})
	} else {
		e = h.Store.Delete(r.Context(), res.table, res.pk, id)
	}
	if e != nil {
		httpx.Error(w, r, 400, "err.conflict")
		return
	}
	httpx.JSON(w, 200, map[string]bool{"success": true})
}
func (h *Handler) action(w http.ResponseWriter, r *http.Request, res resource, tail []string, cols map[string]bool) {
	if len(tail) != 2 || tail[1] != "status" && tail[1] != "complete" && tail[1] != "delete" && tail[1] != "restore" {
		httpx.Error(w, r, 501, "err.not_implemented")
		return
	}
	if r.Method != "PATCH" && r.Method != "POST" {
		httpx.MethodNotAllowed(w, r)
		return
	}
	h.changeState(w, r, res, tail[0], tail[1], cols)
}
func (h *Handler) changeState(w http.ResponseWriter, r *http.Request, res resource, id, action string, cols map[string]bool) {
	fields, e := transitionFields(r, action, cols)
	if e != nil {
		httpx.Error(w, r, 400, "err.invalid_json")
		return
	}
	h.persistState(w, r, res, id, fields, cols)
}
func (h *Handler) persistState(w http.ResponseWriter, r *http.Request, res resource, id string, fields map[string]any, cols map[string]bool) {
	fields = validColumns(fields, cols)
	if cols["update_time"] {
		fields["update_time"] = time.Now()
	}
	if len(fields) == 0 {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	if e := h.Store.Update(r.Context(), res.table, res.pk, id, fields); e != nil {
		httpx.Error(w, r, 400, "err.conflict")
		return
	}
	h.get(w, r, res, id)
}
func transitionFields(r *http.Request, action string, cols map[string]bool) (map[string]any, error) {
	fields := map[string]any{}
	switch action {
	case "complete":
		fields["status"] = "completed"
	case "delete":
		fields["deleted_at"] = time.Now()
	case "restore":
		fields["deleted_at"] = nil
	case "status":
		body, e := decode(r)
		if e != nil {
			return nil, e
		}
		fields = mapFields(body, cols)
	}
	return validColumns(fields, cols), nil
}
func validColumns(fields map[string]any, cols map[string]bool) map[string]any {
	for key := range fields {
		if !cols[key] {
			delete(fields, key)
		}
	}
	return fields
}
func decode(r *http.Request) (map[string]any, error) {
	var body map[string]any
	d := json.NewDecoder(r.Body)
	d.UseNumber()
	e := d.Decode(&body)
	return body, e
}
func mapFields(body map[string]any, cols map[string]bool) map[string]any {
	fields, extra := map[string]any{}, map[string]any{}
	for k, v := range body {
		key := snake(k)
		if cols[key] {
			fields[key] = dbValue(v)
		} else if k != "id" {
			extra[k] = v
		}
	}
	if cols["payload"] && len(extra) > 0 {
		payload := readPayload(fields)
		for k, v := range extra {
			payload[k] = v
		}
		fields["payload"] = jsonText(payload)
	}
	return fields
}
func readPayload(fields map[string]any) map[string]any {
	payload := map[string]any{}
	if s, ok := fields["payload"].(string); ok {
		_ = json.Unmarshal([]byte(s), &payload)
	}
	if payload == nil {
		return map[string]any{}
	}
	return payload
}
func dbValue(v any) any {
	switch x := v.(type) {
	case map[string]any, []any:
		return jsonText(x)
	case json.Number:
		return string(x)
	default:
		return v
	}
}
func makeID(fields map[string]any, res resource, cols map[string]bool) {
	if cols[res.pk] && fields[res.pk] == nil && res.pk != "service_key" {
		fields[res.pk] = uuid()
	}
}
func stamp(fields map[string]any, cols map[string]bool, key string) {
	if cols[key] && fields[key] == nil {
		fields[key] = time.Now()
	}
}
func pageLimit(r *http.Request) int {
	n, e := strconv.Atoi(r.URL.Query().Get("limit"))
	if e != nil || n < 1 {
		return 100
	}
	if n > 500 {
		return 500
	}
	return n
}
func pageOffset(r *http.Request) int {
	n, e := strconv.Atoi(r.URL.Query().Get("offset"))
	if e != nil || n < 0 {
		return 0
	}
	return n
}
func normalizeList(rows []map[string]any) []map[string]any {
	out := make([]map[string]any, len(rows))
	for i, row := range rows {
		out[i] = normalize(row)
	}
	return out
}
func normalize(row map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range row {
		if k == "payload" {
			if raw, ok := v.(string); ok {
				var data map[string]any
				if json.Unmarshal([]byte(raw), &data) == nil {
					for key, value := range data {
						out[key] = value
					}
				}
			}
			continue
		}
		out[camel(k)] = v
	}
	return out
}
func snake(value string) string {
	var b strings.Builder
	for i, r := range value {
		if r >= 'A' && r <= 'Z' {
			if i > 0 {
				b.WriteByte('_')
			}
			b.WriteRune(r + 32)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}
func camel(value string) string {
	parts := strings.Split(value, "_")
	for i := 1; i < len(parts); i++ {
		if parts[i] != "" {
			parts[i] = strings.ToUpper(parts[i][:1]) + parts[i][1:]
		}
	}
	return strings.Join(parts, "")
}
func uuid() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		return fmt.Sprintf("id-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:])
}
func jsonText(v any) string { raw, _ := json.Marshal(v); return string(raw) }
