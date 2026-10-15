package i18n

import "strings"

type Locale string

const (
	English Locale = "en"
	Chinese Locale = "zh-CN"
)

var catalog = map[string]map[Locale]string{
	"err.invalid_json":        {English: "Invalid JSON request body.", Chinese: "请求内容不是有效的 JSON。"},
	"err.unauthorized":        {English: "Authentication is required or the token is invalid.", Chinese: "需要登录或令牌无效。"},
	"err.forbidden":           {English: "You do not have permission to perform this action.", Chinese: "您没有权限执行此操作。"},
	"err.not_found":           {English: "The requested resource was not found.", Chinese: "未找到请求的资源。"},
	"err.not_implemented":     {English: "This feature is not implemented yet.", Chinese: "此功能尚未实现。"},
	"err.database":            {English: "The database is unavailable.", Chinese: "数据库不可用。"},
	"err.validation":          {English: "The request data is invalid.", Chinese: "请求数据无效。"},
	"err.internal":            {English: "An unexpected server error occurred.", Chinese: "服务器发生意外错误。"},
	"err.conflict":            {English: "The requested operation conflicts with existing data.", Chinese: "请求的操作与现有数据冲突。"},
	"err.method":              {English: "This HTTP method is not supported for this route.", Chinese: "此路由不支持该 HTTP 方法。"},
	"err.rate_limited":        {English: "Too many requests. Please try again later.", Chinese: "请求过于频繁，请稍后重试。"},
	"auth.bad_credentials":    {English: "Email or password is incorrect.", Chinese: "邮箱或密码错误。"},
	"auth.disabled":           {English: "This account is disabled.", Chinese: "此账户已停用。"},
	"auth.password_required":  {English: "Old and new passwords are required.", Chinese: "旧密码和新密码不能为空。"},
	"auth.password_short":     {English: "The new password must contain at least 8 characters.", Chinese: "新密码长度不能少于 8 位。"},
	"auth.password_old_wrong": {English: "The old password is incorrect.", Chinese: "旧密码错误。"},
	"auth.password_changed":   {English: "Password changed successfully.", Chinese: "密码修改成功。"},
	"msg.saved":               {English: "Saved successfully.", Chinese: "保存成功。"},
	"msg.deleted":             {English: "Deleted successfully.", Chinese: "删除成功。"},
}

func FromHeader(value string) Locale {
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(value)), "zh") {
		return Chinese
	}
	return English
}
func Text(locale Locale, key string) string {
	if entry, ok := catalog[key]; ok {
		if text, found := entry[locale]; found {
			return text
		}
		return entry[English]
	}
	return catalog["err.internal"][locale]
}
func TranslateSource(locale Locale, source string) string {
	if locale == Chinese {
		return source
	}
	if translated, ok := sourceEnglish[source]; ok {
		return translated
	}
	return source
}
