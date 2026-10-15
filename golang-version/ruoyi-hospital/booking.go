package hospital

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
)

type bookingRequest struct {
	First        string `json:"firstName"`
	Last         string `json:"lastName"`
	Name         string `json:"patientName"`
	Email        string `json:"email"`
	Phone        string `json:"phone"`
	Practitioner string `json:"practitionerId"`
	Room         string `json:"roomId"`
	Service      string `json:"serviceType"`
	Start        string `json:"startTime"`
	End          string `json:"endTime"`
}

func (h *Handler) booking(w http.ResponseWriter, r *http.Request, tail []string) {
	if len(tail) == 1 && tail[0] == "options" && r.Method == "GET" {
		h.bookingOptions(w, r)
		return
	}
	if len(tail) == 1 && (tail[0] == "availability" || tail[0] == "schedule") && r.Method == "GET" {
		h.availability(w, r, tail[0])
		return
	}
	if len(tail) == 0 && r.Method == "POST" {
		h.createBooking(w, r)
		return
	}
	httpx.Error(w, r, 404, "err.not_found")
}
func (h *Handler) bookingOptions(w http.ResponseWriter, r *http.Request) {
	services, e := h.Store.Rows(r.Context(), "SELECT service_key,label,duration,default_price,required_tag FROM tcm_service_type WHERE public_visible=1")
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	rooms, e := h.Store.Rows(r.Context(), "SELECT id,name,support_tags FROM tcm_room WHERE is_active=1")
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	practitioners, e := h.publicPractitioners(r)
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, map[string]any{"serviceTypes": services, "rooms": rooms, "practitioners": practitioners})
}
func (h *Handler) publicPractitioners(r *http.Request) ([]map[string]any, error) {
	return h.Store.Rows(r.Context(), "SELECT DISTINCT u.user_id AS id,u.nick_name AS name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id WHERE u.status='0' AND u.del_flag='0' AND r.status='0' AND r.role_key IN ('doctor','practitioner','tcm_doctor')")
}
func (h *Handler) availability(w http.ResponseWriter, r *http.Request, kind string) {
	date, day, e := requestedDate(r)
	service := r.URL.Query().Get("serviceType")
	if e != nil || service == "" {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	duration := h.serviceDuration(r, service)
	busy, e := h.busyAppointments(r, day)
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	slots := makeSlots(day, duration, busy)
	writeAvailability(w, date, service, kind, duration, slots)
}
func requestedDate(r *http.Request) (string, time.Time, error) {
	date := r.URL.Query().Get("date")
	if date == "" {
		return "", time.Time{}, fmt.Errorf("date required")
	}
	day, e := time.ParseInLocation("2006-01-02", date, clinicLocation())
	return date, day, e
}
func writeAvailability(w http.ResponseWriter, date, service, kind string, duration int, slots []map[string]any) {
	if kind == "schedule" {
		httpx.JSON(w, 200, map[string]any{"date": date, "days": []any{map[string]any{"date": date, "slots": slots}}, "duration": duration})
		return
	}
	httpx.JSON(w, 200, map[string]any{"date": date, "serviceType": service, "duration": duration, "slotStepMinutes": 10, "slots": slots})
}

type busySlot struct{ Start, End time.Time }

func (h *Handler) serviceDuration(r *http.Request, key string) int {
	var d sql.NullInt64
	_ = h.Store.DB.QueryRowContext(r.Context(), "SELECT duration FROM tcm_service_type WHERE service_key=? AND public_visible=1", key).Scan(&d)
	if d.Valid && d.Int64 > 0 {
		return int(d.Int64)
	}
	return 30
}
func (h *Handler) busyAppointments(r *http.Request, day time.Time) ([]busySlot, error) {
	start := time.Date(day.Year(), day.Month(), day.Day(), 0, 0, 0, 0, day.Location())
	end := start.Add(24 * time.Hour)
	rows, e := h.Store.DB.QueryContext(r.Context(), "SELECT start_time,end_time FROM tcm_appointment WHERE start_time>=? AND start_time<? AND status NOT IN ('cancelled','canceled')", start, end)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	busy := []busySlot{}
	for rows.Next() {
		var b busySlot
		if e = rows.Scan(&b.Start, &b.End); e != nil {
			return nil, e
		}
		busy = append(busy, b)
	}
	return busy, rows.Err()
}
func makeSlots(day time.Time, duration int, busy []busySlot) []map[string]any {
	start := time.Date(day.Year(), day.Month(), day.Day(), 9, 0, 0, 0, day.Location())
	close := time.Date(day.Year(), day.Month(), day.Day(), 18, 0, 0, 0, day.Location())
	slots := []map[string]any{}
	for at := start; !at.Add(time.Duration(duration) * time.Minute).After(close); at = at.Add(10 * time.Minute) {
		end := at.Add(time.Duration(duration) * time.Minute)
		if !conflicts(at, end, busy) {
			slots = append(slots, map[string]any{"startTime": at.Format("2006-01-02 15:04:05"), "endTime": end.Format("2006-01-02 15:04:05"), "available": true})
		}
	}
	return slots
}
func conflicts(start, end time.Time, busy []busySlot) bool {
	for _, slot := range busy {
		if start.Before(slot.End) && end.After(slot.Start) {
			return true
		}
	}
	return false
}
func (h *Handler) checkSlot(w http.ResponseWriter, r *http.Request) {
	in, start, end, e := readSlotRequest(r)
	if e != nil {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	query, args := conflictQuery(in, start, end)
	available, e := h.slotAvailable(r, query, args)
	if e != nil {
		httpx.Error(w, r, 500, "err.database")
		return
	}
	httpx.JSON(w, 200, map[string]any{"available": available})
}
func readSlotRequest(r *http.Request) (bookingRequest, time.Time, time.Time, error) {
	var in bookingRequest
	if e := json.NewDecoder(r.Body).Decode(&in); e != nil {
		return in, time.Time{}, time.Time{}, e
	}
	start, e := parseTime(in.Start)
	if e != nil {
		return in, start, time.Time{}, e
	}
	end, e := parseTime(in.End)
	if e != nil || !end.After(start) {
		return in, start, end, fmt.Errorf("invalid interval")
	}
	return in, start, end, nil
}
func (h *Handler) slotAvailable(r *http.Request, query string, args []any) (bool, error) {
	var count int
	e := h.Store.DB.QueryRowContext(r.Context(), query, args...).Scan(&count)
	return count == 0, e
}
func conflictQuery(in bookingRequest, start, end time.Time) (string, []any) {
	query := "SELECT COUNT(*) FROM tcm_appointment WHERE start_time<? AND end_time>? AND status NOT IN ('cancelled','canceled')"
	args := []any{end, start}
	if in.Practitioner != "" {
		query += " AND practitioner_id=?"
		args = append(args, in.Practitioner)
	}
	if in.Room != "" {
		query += " AND room_id=?"
		args = append(args, in.Room)
	}
	return query, args
}
func (h *Handler) createBooking(w http.ResponseWriter, r *http.Request) {
	in, name, start, end, e := parseBooking(r, h.serviceDuration)
	if e != nil {
		httpx.Error(w, r, 400, "err.validation")
		return
	}
	h.saveBooking(w, r, in, name, start, end)
}
func parseBooking(r *http.Request, duration func(*http.Request, string) int) (bookingRequest, string, time.Time, time.Time, error) {
	var in bookingRequest
	if e := json.NewDecoder(r.Body).Decode(&in); e != nil {
		return in, "", time.Time{}, time.Time{}, e
	}
	name := strings.TrimSpace(in.First + " " + in.Last)
	if name == "" {
		name = in.Name
	}
	if name == "" || in.Service == "" {
		return in, "", time.Time{}, time.Time{}, fmt.Errorf("invalid booking")
	}
	start, e := parseTime(in.Start)
	if e != nil {
		return in, "", time.Time{}, time.Time{}, e
	}
	end, e := bookingEnd(in.End, start, duration(r, in.Service))
	return in, name, start, end, e
}
func bookingEnd(raw string, start time.Time, duration int) (time.Time, error) {
	end := start.Add(time.Duration(duration) * time.Minute)
	if raw != "" {
		parsed, e := parseTime(raw)
		if e != nil {
			return time.Time{}, e
		}
		end = parsed
	}
	if !end.After(start) {
		return time.Time{}, fmt.Errorf("end must follow start")
	}
	return end, nil
}
func parseTime(raw string) (time.Time, error) {
	for _, layout := range []string{time.RFC3339, "2006-01-02 15:04:05"} {
		if v, e := time.ParseInLocation(layout, raw, clinicLocation()); e == nil {
			return v, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid datetime")
}
func (h *Handler) saveBooking(w http.ResponseWriter, r *http.Request, in bookingRequest, name string, start, end time.Time) {
	patientID, appointmentID := uuid(), uuid()
	patient, appointment := h.bookingRecords(r, in, name, start, end, patientID, appointmentID)
	e := h.Store.Transaction(r.Context(), func(tx *sql.Tx) error {
		return createBookingTx(r.Context(), h.Store, tx, in, start, end, patient, appointment)
	})
	if e != nil {
		httpx.Error(w, r, 409, "err.conflict")
		return
	}
	h.writeBooking(w, r, appointmentID, patientID)
}
func (h *Handler) bookingRecords(r *http.Request, in bookingRequest, name string, start, end time.Time, patientID, appointmentID string) (map[string]any, map[string]any) {
	patient := map[string]any{"id": patientID, "name": name, "first_name": in.First, "last_name": in.Last, "email": in.Email, "phone": in.Phone, "create_time": time.Now(), "update_time": time.Now()}
	appointment := map[string]any{"id": appointmentID, "patient_id": patientID, "practitioner_id": in.Practitioner, "room_id": in.Room, "service_type": in.Service, "start_time": start, "end_time": end, "status": "booked", "create_time": time.Now(), "update_time": time.Now()}
	pc, _ := h.Store.Columns(r.Context(), "tcm_patient")
	ac, _ := h.Store.Columns(r.Context(), "tcm_appointment")
	return mapFields(patient, pc), mapFields(appointment, ac)
}
func (h *Handler) writeBooking(w http.ResponseWriter, r *http.Request, appointmentID, patientID string) {
	row, e := h.Store.One(r.Context(), "tcm_appointment", "id", appointmentID)
	if e != nil {
		httpx.JSON(w, 201, map[string]any{"success": true, "patientId": patientID})
		return
	}
	httpx.JSON(w, 201, map[string]any{"appointment": normalize(row), "patientId": patientID})
}
func createBookingTx(ctx context.Context, store *database.Store, tx *sql.Tx, in bookingRequest, start, end time.Time, patient, appointment map[string]any) error {
	var service string
	if e := tx.QueryRowContext(ctx, "SELECT service_key FROM tcm_service_type WHERE service_key=? AND public_visible=1 FOR UPDATE", in.Service).Scan(&service); e != nil {
		return e
	}
	query, args := conflictQuery(in, start, end)
	var count int
	if e := tx.QueryRowContext(ctx, query, args...).Scan(&count); e != nil {
		return e
	}
	if count > 0 {
		return sql.ErrNoRows
	}
	if e := store.InsertTx(ctx, tx, "tcm_patient", patient); e != nil {
		return e
	}
	return store.InsertTx(ctx, tx, "tcm_appointment", appointment)
}
func clinicLocation() *time.Location {
	loc, e := time.LoadLocation("America/Toronto")
	if e != nil {
		return time.FixedZone("America/Toronto", -5*3600)
	}
	return loc
}
