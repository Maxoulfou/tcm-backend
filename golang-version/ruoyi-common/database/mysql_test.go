package database

import "testing"

func TestQuoteAllowsIdentifiers(t *testing.T) {
	got, e := Quote("tcm_patient")
	if e != nil || got != "`tcm_patient`" {
		t.Fatalf("%q %v", got, e)
	}
}
func TestQuoteRejectsSQLFragments(t *testing.T) {
	for _, name := range []string{"patients;DROP TABLE sys_user", "name--", "a b", ""} {
		if _, e := Quote(name); e == nil {
			t.Fatalf("accepted %q", name)
		}
	}
}
