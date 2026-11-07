//go:build !windows

package dpi

import (
	"net"

	"golang.org/x/sys/unix"
)

// sendRawOOB sends data with TCP MSG_OOB on POSIX platforms (Linux, Android, Darwin).
func sendRawOOB(tcpConn *net.TCPConn, data []byte) (int, error) {
	rawConn, err := tcpConn.SyscallConn()
	if err != nil {
		return 0, err
	}
	var sendErr error
	err = rawConn.Control(func(fd uintptr) {
		sendErr = unix.Send(int(fd), data, unix.MSG_OOB)
	})
	if err != nil {
		return 0, err
	}
	if sendErr != nil {
		return 0, sendErr
	}
	return len(data), nil
}
