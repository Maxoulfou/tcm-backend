package quartz

import (
	"context"
	"errors"
	"sync"
	"time"
)

type Job struct {
	Name  string
	Every time.Duration
	Run   func(context.Context) error
}
type Scheduler struct {
	mu   sync.RWMutex
	jobs []Job
}

func (s *Scheduler) Register(job Job) error {
	if job.Name == "" || job.Every <= 0 || job.Run == nil {
		return errors.New("invalid scheduled job")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.jobs = append(s.jobs, job)
	return nil
}
func (s *Scheduler) Start(ctx context.Context) {
	s.mu.RLock()
	jobs := append([]Job(nil), s.jobs...)
	s.mu.RUnlock()
	for _, job := range jobs {
		go s.run(ctx, job)
	}
}
func (s *Scheduler) run(ctx context.Context, job Job) {
	ticker := time.NewTicker(job.Every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_ = job.Run(ctx)
		}
	}
}
