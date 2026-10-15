package admin

import (
	"net/http/httptest"
	"testing"

	"github.com/Maxoulfou/tcm-backend/golang-version/ruoyi-framework/security"
)

func TestHealthIsPublic(t *testing.T) {
	jwt, _ := security.NewJWT("01234567890123456789012345678901", "test")
	server := httptest.NewServer(New(nil, jwt, "http://localhost:5173"))
	defer server.Close()
	response, e := server.Client().Get(server.URL + "/health")
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatalf("status %d", response.StatusCode)
	}
}
func TestApiRequiresJwt(t *testing.T) {
	jwt, _ := security.NewJWT("01234567890123456789012345678901", "test")
	handler := New(nil, jwt, "http://localhost:5173")
	request := httptest.NewRequest("GET", "/api/patients", nil)
	record := httptest.NewRecorder()
	handler.ServeHTTP(record, request)
	if record.Code != 401 {
		t.Fatalf("status %d", record.Code)
	}
}
