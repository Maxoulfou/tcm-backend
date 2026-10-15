package hospital

import (
	"net/http"
	"strings"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/database"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-common/httpx"
	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

type Handler struct{ Store *database.Store }
type resource struct{ table, pk string }

var resources = map[string]resource{
	"patients": {"tcm_patient", "id"}, "appointments": {"tcm_appointment", "id"}, "consultations": {"tcm_consultation", "id"},
	"branches": {"tcm_branch", "id"}, "formulas": {"tcm_formula", "id"}, "herb-dict": {"tcm_herb_dict", "id"},
	"inventory": {"tcm_inventory_item", "id"}, "meridians": {"tcm_meridian", "id"}, "acupoints": {"tcm_acupoint", "id"},
	"suppliers": {"tcm_supplier", "id"}, "templates": {"tcm_treatment_template", "id"}, "unit-conversions": {"tcm_unit_conversion", "id"},
	"rooms": {"tcm_room", "id"}, "price-lists": {"tcm_price_list", "id"}, "service-types": {"tcm_service_type", "service_key"},
	"email-logs": {"tcm_email_log", "id"}, "files": {"tcm_patient_file", "id"},
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	route, tail, ok := pathParts(r.URL.Path)
	if !ok {
		httpx.Error(w, r, 404, "err.not_found")
		return
	}
	h.dispatch(w, r, route, tail)
}
func pathParts(path string) (string, []string, bool) {
	trimmed := strings.Trim(strings.TrimPrefix(path, "/api/"), "/")
	if trimmed == "" {
		return "", nil, false
	}
	parts := strings.Split(trimmed, "/")
	return parts[0], parts[1:], true
}
func (h *Handler) dispatch(w http.ResponseWriter, r *http.Request, route string, tail []string) {
	if route == "public-booking" {
		h.publicBooking(w, r, tail)
		return
	}
	res, ok := resources[route]
	if !ok {
		httpx.Error(w, r, 404, "err.not_found")
		return
	}
	h.authorized(w, r, res, route, tail)
}
func (h *Handler) authorized(w http.ResponseWriter, r *http.Request, res resource, route string, tail []string) {
	id, ok := security.IdentityFrom(r.Context())
	if !ok {
		httpx.Error(w, r, 401, "err.unauthorized")
		return
	}
	if !allowed(id, route, r.Method) {
		httpx.Error(w, r, 403, "err.forbidden")
		return
	}
	if route == "appointments" && len(tail) == 1 && tail[0] == "check-slot" {
		if r.Method != "POST" {
			httpx.MethodNotAllowed(w, r)
			return
		}
		h.checkSlot(w, r)
		return
	}
	h.resource(w, r, res, tail)
}
func (h *Handler) publicBooking(w http.ResponseWriter, r *http.Request, tail []string) {
	h.booking(w, r, tail)
}
func allowed(id security.Identity, route, method string) bool {
	roles := rolesFor(route)
	if method != "GET" && route != "settings" && !hasAny(id, "admin", "practitioner", "doctor") {
		return false
	}
	return hasAny(id, roles...)
}
func rolesFor(route string) []string {
	switch route {
	case "patients", "appointments", "consultations", "files":
		return []string{"admin", "practitioner", "doctor", "apprentice"}
	case "inventory", "herb-dict", "acupoints", "meridians", "formulas", "unit-conversions":
		return []string{"admin", "practitioner", "doctor", "pharmacist"}
	default:
		return []string{"admin"}
	}
}
func hasAny(id security.Identity, wants ...string) bool {
	for _, role := range id.Roles {
		for _, want := range wants {
			if role == want || (role == "doctor" && want == "practitioner") {
				return true
			}
		}
	}
	return false
}
