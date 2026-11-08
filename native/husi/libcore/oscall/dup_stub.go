//go:build !(linux || darwin)

package oscall

import (
	"os"
)

func Dup3(oldFd, newFd, flags int) error {
	return nil
}

func Dup(oldFd int) (int, error) {
	return 0, os.ErrInvalid
}
