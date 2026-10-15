package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

func (a *app) signToken(c claims) (string, error) {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	body, e := json.Marshal(c)
	if e != nil {
		return "", e
	}
	payload := header + "." + base64.RawURLEncoding.EncodeToString(body)
	m := hmac.New(sha256.New, a.secret)
	_, _ = m.Write([]byte(payload))
	return payload + "." + base64.RawURLEncoding.EncodeToString(m.Sum(nil)), nil
}
func (a *app) parseToken(token string) (claims, error) {
	var c claims
	p := strings.Split(token, ".")
	if len(p) != 3 {
		return c, fmt.Errorf("malformed JWT")
	}
	sig, e := base64.RawURLEncoding.DecodeString(p[2])
	if e != nil {
		return c, e
	}
	m := hmac.New(sha256.New, a.secret)
	_, _ = m.Write([]byte(p[0] + "." + p[1]))
	if !hmac.Equal(sig, m.Sum(nil)) {
		return c, fmt.Errorf("bad signature")
	}
	body, e := base64.RawURLEncoding.DecodeString(p[1])
	if e != nil {
		return c, e
	}
	if e = json.Unmarshal(body, &c); e != nil {
		return c, e
	}
	if c.Exp <= time.Now().Unix() {
		return c, fmt.Errorf("expired token")
	}
	return c, nil
}

var identifier = regexp.MustCompile(`^[A-Za-z0-9_]+$`)

func qi(s string) string {
	if !identifier.MatchString(s) {
		panic("unsafe SQL identifier")
	}
	return "`" + s + "`"
}
func (a *app) columns(table string) (map[string]bool, error) {
	rows, e := a.db.Query("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?", table)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var s string
		if e = rows.Scan(&s); e != nil {
			return nil, e
		}
		out[s] = true
	}
	return out, rows.Err()
}
func (a *app) one(res resource, id string) (map[string]any, error) {
	rows, e := a.db.Query("SELECT * FROM "+qi(res.table)+" WHERE "+qi(res.pk)+"=? LIMIT 1", id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	list, e := scanRows(rows)
	if e != nil {
		return nil, e
	}
	if len(list) == 0 {
		return nil, sql.ErrNoRows
	}
	return list[0], nil
}
func (a *app) insert(table string, f map[string]any) error {
	keys := sortedKeys(f)
	if len(keys) == 0 {
		return fmt.Errorf("no fields")
	}
	names, marks, vals := make([]string, len(keys)), make([]string, len(keys)), make([]any, len(keys))
	for i, k := range keys {
		names[i] = qi(k)
		marks[i] = "?"
		vals[i] = dbValue(f[k])
	}
	_, e := a.db.Exec("INSERT INTO "+qi(table)+" ("+strings.Join(names, ",")+") VALUES ("+strings.Join(marks, ",")+")", vals...)
	return e
}
func (a *app) update(res resource, id string, f map[string]any) error {
	keys := sortedKeys(f)
	if len(keys) == 0 {
		return fmt.Errorf("no fields")
	}
	sets := make([]string, len(keys))
	vals := make([]any, len(keys)+1)
	for i, k := range keys {
		sets[i] = qi(k) + "=?"
		vals[i] = dbValue(f[k])
	}
	vals[len(keys)] = id
	_, e := a.db.Exec("UPDATE "+qi(res.table)+" SET "+strings.Join(sets, ",")+" WHERE "+qi(res.pk)+"=?", vals...)
	return e
}
func mapInput(in map[string]any, cols map[string]bool) map[string]any {
	out, extra := map[string]any{}, map[string]any{}
	for k, v := range in {
		dbk := snake(k)
		if cols[dbk] {
			out[dbk] = dbValue(v)
		} else if k != "id" && k != "createdAt" && k != "updatedAt" {
			extra[k] = v
		}
	}
	if cols["payload"] && len(extra) > 0 {
		p := map[string]any{}
		if v, ok := out["payload"]; ok {
			switch x := v.(type) {
			case string:
				_ = json.Unmarshal([]byte(x), &p)
			case []byte:
				_ = json.Unmarshal(x, &p)
			}
		}
		if p == nil {
			p = map[string]any{}
		}
		for k, v := range extra {
			p[k] = v
		}
		b, _ := json.Marshal(p)
		out["payload"] = string(b)
	}
	return out
}
func dbValue(v any) any {
	switch x := v.(type) {
	case map[string]any, []any:
		b, _ := json.Marshal(x)
		return string(b)
	case json.Number:
		if _, e := strconv.ParseInt(string(x), 10, 64); e == nil {
			return string(x)
		}
		if _, e := strconv.ParseFloat(string(x), 64); e == nil {
			return string(x)
		}
		return string(x)
	default:
		return v
	}
}
func scanRows(rows *sql.Rows) ([]map[string]any, error) {
	cols, e := rows.Columns()
	if e != nil {
		return nil, e
	}
	var out []map[string]any
	for rows.Next() {
		vals := make([]any, len(cols))
		ptr := make([]any, len(cols))
		for i := range vals {
			ptr[i] = &vals[i]
		}
		if e = rows.Scan(ptr...); e != nil {
			return nil, e
		}
		m := map[string]any{}
		for i, k := range cols {
			switch v := vals[i].(type) {
			case []byte:
				m[k] = string(v)
			case time.Time:
				m[k] = v.Format("2006-01-02 15:04:05")
			default:
				m[k] = v
			}
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
func normalizeRow(in map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range in {
		if k == "payload" {
			if s, ok := v.(string); ok {
				var extra map[string]any
				if json.Unmarshal([]byte(s), &extra) == nil {
					for x, y := range extra {
						out[x] = y
					}
				}
			}
			continue
		}
		out[camel(k)] = v
	}
	return out
}
func snake(s string) string {
	var b strings.Builder
	for i, r := range s {
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
func camel(s string) string {
	p := strings.Split(s, "_")
	for i := 1; i < len(p); i++ {
		if p[i] != "" {
			p[i] = strings.ToUpper(p[i][:1]) + p[i][1:]
		}
	}
	return strings.Join(p, "")
}
func sortedKeys(m map[string]any) []string {
	k := make([]string, 0, len(m))
	for x := range m {
		k = append(k, x)
	}
	sort.Strings(k)
	return k
}
func empty(v any) bool { return v == nil || v == "" }
func randomID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	s := fmt.Sprintf("%x", b)
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}
func decode(r *http.Request, dst any) error {
	d := json.NewDecoder(r.Body)
	d.UseNumber()
	return d.Decode(dst)
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]any{"status": status, "message": msg})
}
func methodNotAllowed(w http.ResponseWriter) { writeError(w, 405, "method not allowed") }
func filterFields(in map[string]any, cols map[string]bool) map[string]any {
	out := map[string]any{}
	for k, v := range in {
		if cols[k] && v != nil {
			out[k] = dbValue(v)
		}
	}
	return out
}
func mustColumns(a *app, t string) map[string]bool { c, _ := a.columns(t); return c }
func (a *app) hasRoleRequest(r *http.Request, want string) bool {
	c, e := a.parseToken(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	return e == nil && hasRole(c, want)
}
func hasRoleRequest(a *app, r *http.Request, want string) bool { return a.hasRoleRequest(r, want) }
func jsonString(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
