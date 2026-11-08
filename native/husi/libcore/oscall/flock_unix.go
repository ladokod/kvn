//go:build unix

package oscall

import (
	"os"

	"golang.org/x/sys/unix"
)

func Flock(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX)
}

func FUnlock(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_UN)
}
