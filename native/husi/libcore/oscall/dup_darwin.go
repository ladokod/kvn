package oscall

import (
	"golang.org/x/sys/unix"
)

func Dup3(oldFd, newFd, flags int) error {
	return unix.Dup2(oldFd, newFd)
}

func Dup(oldFd int) (int, error) {
	return unix.Dup(oldFd)
}
