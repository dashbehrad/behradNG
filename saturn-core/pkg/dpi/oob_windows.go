//go:build windows

package dpi

import (
	"net"

	"golang.org/x/sys/windows"
)

// sendRawOOB sends data with TCP MSG_OOB on Windows via WSASend.
func sendRawOOB(tcpConn *net.TCPConn, data []byte) (int, error) {
	rawConn, err := tcpConn.SyscallConn()
	if err != nil {
		return 0, err
	}
	var sent int
	var sendErr error
	err = rawConn.Control(func(fd uintptr) {
		var wsaBuf windows.WSABuf
		wsaBuf.Len = uint32(len(data))
		if len(data) > 0 {
			wsaBuf.Buf = &data[0]
		}
		var bytesSent uint32
		sendErr = windows.WSASend(windows.Handle(fd), &wsaBuf, 1, &bytesSent, windows.MSG_OOB, nil, nil)
		sent = int(bytesSent)
	})
	if err != nil {
		return 0, err
	}
	if sendErr != nil {
		return 0, sendErr
	}
	return sent, nil
}
