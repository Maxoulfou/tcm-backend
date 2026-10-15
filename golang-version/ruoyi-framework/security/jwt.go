package security

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

type Identity struct {
	UserID    string   `json:"sub"`
	Name      string   `json:"name"`
	Email     string   `json:"email"`
	Roles     []string `json:"roles"`
	Issuer    string   `json:"iss"`
	Expires   int64    `json:"exp"`
	NotBefore int64    `json:"nbf,omitempty"`
}
type JWT struct {
	secret []byte
	issuer string
}

func NewJWT(secret, issuer string) (*JWT, error) {
	if len(secret) < 32 {
		return nil, errors.New("JWT secret must be at least 32 bytes")
	}
	return &JWT{[]byte(secret), issuer}, nil
}
func (j *JWT) Sign(id Identity) (string, error) {
	id.Issuer = j.issuer
	id.Expires = time.Now().Add(12 * time.Hour).Unix()
	head := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	body, e := json.Marshal(id)
	if e != nil {
		return "", e
	}
	input := head + "." + base64.RawURLEncoding.EncodeToString(body)
	return input + "." + j.mac(input), nil
}
func (j *JWT) mac(input string) string {
	m := hmac.New(sha256.New, j.secret)
	_, _ = m.Write([]byte(input))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}
func (j *JWT) Parse(token string) (Identity, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Identity{}, errors.New("malformed token")
	}
	if !j.validSignature(parts) {
		return Identity{}, errors.New("invalid signature")
	}
	if e := j.validHeader(parts[0]); e != nil {
		return Identity{}, e
	}
	return j.decodeClaims(parts[1])
}
func (j *JWT) validSignature(parts []string) bool {
	return hmac.Equal([]byte(parts[2]), []byte(j.mac(parts[0]+"."+parts[1])))
}
func (j *JWT) validHeader(raw string) error {
	body, e := base64.RawURLEncoding.DecodeString(raw)
	if e != nil {
		return e
	}
	var head struct {
		Alg string `json:"alg"`
	}
	if e = json.Unmarshal(body, &head); e != nil {
		return e
	}
	if head.Alg != "HS256" {
		return errors.New("unsupported signing algorithm")
	}
	return nil
}
func (j *JWT) decodeClaims(raw string) (Identity, error) {
	body, e := base64.RawURLEncoding.DecodeString(raw)
	if e != nil {
		return Identity{}, e
	}
	var id Identity
	if e = json.Unmarshal(body, &id); e != nil {
		return id, e
	}
	if id.Issuer != j.issuer || id.Expires <= time.Now().Unix() || id.UserID == "" || id.NotBefore > time.Now().Unix() {
		return Identity{}, errors.New("invalid claims")
	}
	return id, nil
}
