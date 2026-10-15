package i18n

import "testing"

func TestErrorMessagesAreLocalized(t *testing.T) {
	if got := Text(English, "err.invalid_json"); got != "Invalid JSON request body." {
		t.Fatal(got)
	}
	if got := Text(Chinese, "err.invalid_json"); got != "请求内容不是有效的 JSON。" {
		t.Fatal(got)
	}
}
func TestLanguageHeaderSelection(t *testing.T) {
	if FromHeader("zh-CN,zh;q=0.9") != Chinese {
		t.Fatal("Chinese locale not selected")
	}
	if FromHeader("fr-CA,fr;q=0.9") != English {
		t.Fatal("fallback locale is not English")
	}
}
func TestTranslateSourceMessage(t *testing.T) {
	if got := TranslateSource(English, "邮箱不能为空"); got != "Email cannot be empty" {
		t.Fatal(got)
	}
	if got := TranslateSource(Chinese, "邮箱不能为空"); got != "邮箱不能为空" {
		t.Fatal(got)
	}
}
