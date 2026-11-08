//go:build !(linux || darwin)

package libcore

import (
	"os"
)

func tunnelName(fd int32) (string, error) {
	return "", os.ErrInvalid
}
