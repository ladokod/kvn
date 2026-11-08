//go:build android

package libcore

import (
	"context"
	"errors"
)

// StartConfig exposes the pinned Husi host without its profile/plugin protobuf API.
// KVN supports only the built-in Naive outbound; no external processes are started.
func (s *Service) StartConfig(config string) error {
	s.access.RLock()
	host := s.host
	s.access.RUnlock()
	if host == nil {
		return errors.New("service not started")
	}
	return host.StartOrReload(context.Background(), config)
}

// CheckKvnConfig validates the actual distro, including compiled-in Naive.
func CheckKvnConfig(config string) error { return checkConfig(config) }
