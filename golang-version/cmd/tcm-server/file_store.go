package main

import (
	"archive/zip"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func (a *app) filesAPI(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 1 && tail[0] == "upload" && r.Method == "POST" {
		a.uploadFile(w, r)
		return
	}
	if len(tail) == 2 && tail[0] == "patient" && r.Method == "GET" {
		rows, e := a.fileRows("patient_id", tail[1])
		if e != nil {
			writeError(w, 500, "file list failed")
			return
		}
		writeJSON(w, 200, rows)
		return
	}
	if len(tail) == 3 && tail[0] == "patient" && tail[2] == "download-all" && r.Method == "GET" {
		rows, e := a.fileRows("patient_id", tail[1])
		if e != nil {
			writeError(w, 500, "file list failed")
			return
		}
		a.zipFiles(w, rows)
		return
	}
	if len(tail) == 2 && tail[0] == "consultation" && r.Method == "GET" {
		rows, e := a.fileRows("consultation_id", tail[1])
		if e != nil {
			writeError(w, 500, "file list failed")
			return
		}
		writeJSON(w, 200, rows)
		return
	}
	if len(tail) == 1 && tail[0] == "access-url" && r.Method == "GET" {
		id := r.URL.Query().Get("fileId")
		if id == "" {
			id = r.URL.Query().Get("id")
		}
		row, e := a.fileRecord(id)
		if e != nil {
			writeError(w, 404, "file not found")
			return
		}
		token := a.fileToken(id, time.Now().Add(15*time.Minute).Unix())
		base := strings.TrimRight(env("PUBLIC_API_BASE_URL", "http://127.0.0.1:8080"), "/")
		writeJSON(w, 200, map[string]any{"url": base + "/api/public/files/access?token=" + token, "expiresIn": 900, "file": normalizeRow(row)})
		return
	}
	if len(tail) == 1 && r.Method == "DELETE" {
		row, e := a.fileRecord(tail[0])
		if e != nil {
			writeError(w, 404, "file not found")
			return
		}
		if _, e = a.db.Exec("DELETE FROM tcm_patient_file WHERE id=?", tail[0]); e != nil {
			writeError(w, 500, "file metadata delete failed")
			return
		}
		if p, e := a.safeFilePath(stringValue(row["file_path"])); e == nil {
			_ = os.Remove(p)
		}
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	writeError(w, 404, "unknown file route")
}
func (a *app) uploadFile(w http.ResponseWriter, r *http.Request) {
	if e := r.ParseMultipartForm(8 << 20); e != nil {
		writeError(w, 400, "invalid multipart upload")
		return
	}
	f, h, e := r.FormFile("file")
	if e != nil {
		writeError(w, 400, "file field is required")
		return
	}
	defer f.Close()
	if h.Size > 8<<20 {
		writeError(w, 413, "file exceeds 8 MiB limit")
		return
	}
	original := filepath.Base(filepath.Clean(h.Filename))
	if original == "." || original == "/" || strings.TrimSpace(original) == "" {
		writeError(w, 400, "invalid filename")
		return
	}
	id := randomID()
	ext := filepath.Ext(original)
	stored := id + ext
	root := storageRoot()
	if e = os.MkdirAll(root, 0700); e != nil {
		writeError(w, 500, "storage directory unavailable")
		return
	}
	dst := filepath.Join(root, stored)
	out, e := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		writeError(w, 500, "could not create stored file")
		return
	}
	_, copyErr := io.Copy(out, io.LimitReader(f, 8<<20+1))
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(dst)
		writeError(w, 500, "file write failed")
		return
	}
	relative := filepath.ToSlash(stored)
	res, e := a.db.Exec("INSERT INTO tcm_patient_file(patient_id,consultation_id,file_type,file_name,file_path,upload_time) VALUES(?,?,?,?,?,NOW())", emptyToNil(r.FormValue("patientId")), emptyToNil(r.FormValue("consultationId")), emptyToNil(r.FormValue("fileType")), original, relative)
	if e != nil {
		_ = os.Remove(dst)
		writeError(w, 500, "could not save file metadata")
		return
	}
	fileID, e := res.LastInsertId()
	if e != nil {
		writeError(w, 500, "could not read file identifier")
		return
	}
	writeJSON(w, 201, map[string]any{"id": fileID, "patientId": r.FormValue("patientId"), "consultationId": r.FormValue("consultationId"), "fileType": r.FormValue("fileType"), "fileName": original, "uploadTime": time.Now().Format(time.RFC3339)})
}
func emptyToNil(s string) any {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	return s
}
func storageRoot() string { return env("STORAGE_DIR", "data/files") }
func (a *app) safeFilePath(rel string) (string, error) {
	if rel == "" || filepath.IsAbs(rel) {
		return "", fmt.Errorf("invalid path")
	}
	root, e := filepath.Abs(storageRoot())
	if e != nil {
		return "", e
	}
	p, e := filepath.Abs(filepath.Join(root, filepath.Clean(rel)))
	if e != nil {
		return "", e
	}
	if !strings.HasPrefix(p, root+string(os.PathSeparator)) {
		return "", fmt.Errorf("path outside storage root")
	}
	return p, nil
}
func (a *app) fileRows(column, id string) ([]map[string]any, error) {
	if column != "patient_id" && column != "consultation_id" {
		return nil, fmt.Errorf("invalid file query")
	}
	rows, e := a.db.Query("SELECT id,patient_id,consultation_id,file_type,file_name,upload_time FROM tcm_patient_file WHERE "+qi(column)+"=? ORDER BY upload_time DESC", id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	return scanRows(rows)
}
func (a *app) fileRecord(id string) (map[string]any, error) {
	rows, e := a.db.Query("SELECT * FROM tcm_patient_file WHERE id=? LIMIT 1", id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	all, e := scanRows(rows)
	if e != nil || len(all) == 0 {
		if e == nil {
			e = fmt.Errorf("not found")
		}
		return nil, e
	}
	return all[0], nil
}
func (a *app) fileToken(id string, exp int64) string {
	payload := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf("%s|%d", id, exp)))
	mac := hmac.New(sha256.New, a.secret)
	_, _ = mac.Write([]byte(payload))
	return payload + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func (a *app) parseFileToken(t string) (string, bool) {
	p := strings.Split(t, ".")
	if len(p) != 2 {
		return "", false
	}
	sig, e := base64.RawURLEncoding.DecodeString(p[1])
	if e != nil {
		return "", false
	}
	mac := hmac.New(sha256.New, a.secret)
	_, _ = mac.Write([]byte(p[0]))
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return "", false
	}
	raw, e := base64.RawURLEncoding.DecodeString(p[0])
	if e != nil {
		return "", false
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 2 {
		return "", false
	}
	exp, e := strconv.ParseInt(parts[1], 10, 64)
	if e != nil || exp < time.Now().Unix() {
		return "", false
	}
	return parts[0], true
}
func (a *app) publicFile(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) != 2 || tail[0] != "files" || tail[1] != "access" || r.Method != "GET" {
		writeError(w, 404, "unknown public file route")
		return
	}
	id, ok := a.parseFileToken(r.URL.Query().Get("token"))
	if !ok {
		writeError(w, 403, "invalid or expired file access token")
		return
	}
	row, e := a.fileRecord(id)
	if e != nil {
		writeError(w, 404, "file not found")
		return
	}
	path, e := a.safeFilePath(stringValue(row["file_path"]))
	if e != nil {
		writeError(w, 404, "file not found")
		return
	}
	f, e := os.Open(path)
	if e != nil {
		writeError(w, 404, "file not found")
		return
	}
	defer f.Close()
	name := filepath.Base(stringValue(row["file_name"]))
	w.Header().Set("Content-Disposition", "attachment; filename=\""+strings.ReplaceAll(name, "\"", "")+"\"")
	http.ServeContent(w, r, name, time.Time{}, f)
}
func (a *app) zipFiles(w http.ResponseWriter, rows []map[string]any) {
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", "attachment; filename=patient-files.zip")
	zw := zip.NewWriter(w)
	for _, row := range rows {
		path, e := a.safeFilePath(stringValue(row["file_path"]))
		if e != nil {
			continue
		}
		src, e := os.Open(path)
		if e != nil {
			continue
		}
		name := filepath.Base(stringValue(row["file_name"]))
		if name == "." || name == "" {
			name = filepath.Base(path)
		}
		dst, e := zw.Create(name)
		if e == nil {
			_, _ = io.Copy(dst, src)
		}
		_ = src.Close()
	}
	if e := zw.Close(); e != nil {
		return
	}
}
