package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func (a *app) patientTokenLink(w http.ResponseWriter, r *http.Request, id, kind string) {
	if r.Method != "POST" {
		methodNotAllowed(w)
		return
	}
	row, e := a.one(resources["patients"], id)
	if e != nil {
		writeError(w, 404, "patient not found")
		return
	}
	email := stringValue(row["email"])
	if strings.TrimSpace(email) == "" {
		writeError(w, 400, "patient email is required")
		return
	}
	p := payload(row)
	token := strings.ReplaceAll(randomID(), "-", "")
	field, expiryField := "consentToken", "consentTokenExpiresAt"
	if kind == "intake" {
		field, expiryField = "intakeToken", "intakeTokenExpiresAt"
	}
	p[field] = token
	p[expiryField] = time.Now().Add(7 * 24 * time.Hour).Format(time.RFC3339)
	raw, _ := json.Marshal(p)
	_, e = a.db.Exec("UPDATE tcm_patient SET payload=?,update_time=NOW() WHERE id=?", string(raw), id)
	if e != nil {
		writeError(w, 500, "could not create public token")
		return
	}
	base := strings.TrimRight(env("PUBLIC_APP_BASE_URL", "http://127.0.0.1:5173"), "/")
	path := "/consent/"
	if kind == "intake" {
		path = "/intake/"
	}
	writeJSON(w, 200, map[string]any{"sent": false, "message": "Token created; configure SMTP to deliver the email", "token": token, "patientName": row["name"], "email": email, "publicLink": base + path + token})
}

func (a *app) consent(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 0 {
		writeError(w, 404, "consent token required")
		return
	}
	token := tail[0]
	if len(tail) == 1 && r.Method == "GET" {
		row, e := a.findPatientToken("consentToken", token)
		if e != nil {
			writeError(w, 404, "consent link is invalid or expired")
			return
		}
		writeJSON(w, 200, map[string]any{"patient": map[string]any{"id": row["id"], "name": row["name"], "firstName": row["first_name"], "lastName": row["last_name"]}, "consentSigned": row["consent_signed"]})
		return
	}
	if len(tail) == 2 && tail[1] == "sign" && r.Method == "POST" {
		row, e := a.findPatientToken("consentToken", token)
		if e != nil {
			writeError(w, 404, "consent link is invalid or expired")
			return
		}
		var in map[string]any
		if decode(r, &in) != nil {
			writeError(w, 400, "invalid JSON")
			return
		}
		signature := strings.TrimSpace(stringValue(in["signatureName"]))
		if signature == "" {
			writeError(w, 400, "signatureName is required")
			return
		}
		p := payload(row)
		p["consentSignatureName"] = signature
		p["consentSectionAcknowledgements"] = in["sectionAcknowledgements"]
		delete(p, "consentToken")
		delete(p, "consentTokenExpiresAt")
		raw, _ := json.Marshal(p)
		_, e = a.db.Exec("UPDATE tcm_patient SET consent_signed=1,consent_signed_at=NOW(),payload=?,update_time=NOW() WHERE id=?", string(raw), row["id"])
		if e != nil {
			writeError(w, 500, "consent could not be saved")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true, "consentSigned": true})
		return
	}
	writeError(w, 404, "unknown consent route")
}

func (a *app) intake(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 0 {
		writeError(w, 404, "intake token required")
		return
	}
	token := tail[0]
	row, e := a.findPatientToken("intakeToken", token)
	if e != nil {
		writeError(w, 404, "intake link is invalid or expired")
		return
	}
	if len(tail) == 1 && r.Method == "GET" {
		p := payload(row)
		writeJSON(w, 200, map[string]any{"patient": map[string]any{"id": row["id"], "name": row["name"], "firstName": row["first_name"], "lastName": row["last_name"]}, "intake": p["intake"], "submitted": p["intakeSubmitted"]})
		return
	}
	if len(tail) == 2 && tail[1] == "submit" && r.Method == "POST" {
		var body map[string]any
		if decode(r, &body) != nil {
			writeError(w, 400, "invalid JSON")
			return
		}
		p := payload(row)
		p["intake"] = body
		p["intakeSubmitted"] = true
		p["intakeSubmittedAt"] = time.Now().Format(time.RFC3339)
		delete(p, "intakeToken")
		delete(p, "intakeTokenExpiresAt")
		raw, _ := json.Marshal(p)
		_, e = a.db.Exec("UPDATE tcm_patient SET payload=?,update_time=NOW() WHERE id=?", string(raw), row["id"])
		if e != nil {
			writeError(w, 500, "intake submission could not be saved")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	if len(tail) == 2 && tail[1] == "cancel" && r.Method == "POST" {
		p := payload(row)
		delete(p, "intakeToken")
		delete(p, "intakeTokenExpiresAt")
		raw, _ := json.Marshal(p)
		_, e = a.db.Exec("UPDATE tcm_patient SET payload=?,update_time=NOW() WHERE id=?", string(raw), row["id"])
		if e != nil {
			writeError(w, 500, "could not cancel intake link")
			return
		}
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	writeError(w, 404, "unknown intake route")
}

func (a *app) findPatientToken(key, token string) (map[string]any, error) {
	if key != "consentToken" && key != "intakeToken" {
		return nil, fmt.Errorf("invalid token kind")
	}
	rows, e := a.db.Query("SELECT * FROM tcm_patient WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,?))=? AND deleted_at IS NULL LIMIT 1", "$."+key, token)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out, e := scanRows(rows)
	if e != nil {
		return nil, e
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("token not found")
	}
	row := out[0]
	p := payload(row)
	expiryKey := key + "ExpiresAt"
	if raw, ok := p[expiryKey].(string); ok && raw != "" {
		expires, e := time.Parse(time.RFC3339, raw)
		if e != nil || time.Now().After(expires) {
			return nil, fmt.Errorf("expired")
		}
	}
	return row, nil
}

func (a *app) signPatientConsent(w http.ResponseWriter, r *http.Request, id string) {
	var in map[string]any
	if decode(r, &in) != nil {
		writeError(w, 400, "invalid JSON")
		return
	}
	signature := strings.TrimSpace(stringValue(in["signatureName"]))
	if signature == "" {
		writeError(w, 400, "signatureName is required")
		return
	}
	row, e := a.one(resources["patients"], id)
	if e != nil {
		writeError(w, 404, "patient not found")
		return
	}
	p := payload(row)
	p["consentSignatureName"] = signature
	p["consentSectionAcknowledgements"] = in["sectionAcknowledgements"]
	raw, _ := json.Marshal(p)
	_, e = a.db.Exec("UPDATE tcm_patient SET consent_signed=1,consent_signed_at=NOW(),payload=?,update_time=NOW() WHERE id=?", string(raw), id)
	if e != nil {
		writeError(w, 500, "consent could not be saved")
		return
	}
	row, _ = a.one(resources["patients"], id)
	writeJSON(w, 200, normalizeRow(row))
}
