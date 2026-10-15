package hospital

import (
	"testing"
	"time"
)

func TestMakeSlotsExcludesBusyIntervals(t *testing.T) {
	day := time.Date(2026, 10, 15, 0, 0, 0, 0, clinicLocation())
	busy := []busySlot{{Start: day.Add(9 * time.Hour), End: day.Add(9*time.Hour + 30*time.Minute)}}
	slots := makeSlots(day, 60, busy)
	if len(slots) != 46 {
		t.Fatalf("got %d slots", len(slots))
	}
}
func TestConflictsUsesHalfOpenIntervals(t *testing.T) {
	start := time.Date(2026, 10, 15, 10, 0, 0, 0, time.UTC)
	busy := []busySlot{{Start: start.Add(-time.Hour), End: start}}
	if conflicts(start, start.Add(time.Hour), busy) {
		t.Fatal("adjacent interval should not overlap")
	}
}
