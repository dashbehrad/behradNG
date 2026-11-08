//go:build !windows

package sysproxy

func SetSystemProxy(addr string) error {
	return nil
}

func ClearSystemProxy() error {
	return nil
}

func NotifyWinINet() {}

func RegisterCloseHandler(cleanup func()) {}
