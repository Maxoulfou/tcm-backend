package security

import "testing"

func TestJWTRoundTrip(t *testing.T) {
	jwt, e := NewJWT("01234567890123456789012345678901", "test")
	if e != nil {
		t.Fatal(e)
	}
	token, e := jwt.Sign(Identity{UserID: "42", Roles: []string{"admin"}})
	if e != nil {
		t.Fatal(e)
	}
	claims, e := jwt.Parse(token)
	if e != nil {
		t.Fatal(e)
	}
	if claims.UserID != "42" || claims.Roles[0] != "admin" {
		t.Fatalf("unexpected claims: %+v", claims)
	}
}
func TestJWTRejectsAlteredSignature(t *testing.T) {
	jwt, _ := NewJWT("01234567890123456789012345678901", "test")
	token, _ := jwt.Sign(Identity{UserID: "42"})
	altered := token[:len(token)-1] + "x"
	if _, e := jwt.Parse(altered); e == nil {
		t.Fatal("altered token accepted")
	}
}
func TestJWTRequiresLongSecret(t *testing.T) {
	if _, e := NewJWT("short", "test"); e == nil {
		t.Fatal("short key accepted")
	}
}
