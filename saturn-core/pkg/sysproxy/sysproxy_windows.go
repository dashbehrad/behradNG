//go:build windows

package sysproxy

import (
	"log"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

var (
	modwininet                = syscall.NewLazyDLL("wininet.dll")
	procInternetSetOption     = modwininet.NewProc("InternetSetOptionW")
	modkernel32               = syscall.NewLazyDLL("kernel32.dll")
	procSetConsoleCtrlHandler = modkernel32.NewProc("SetConsoleCtrlHandler")
)

const (
	INTERNET_OPTION_REFRESH          = 37
	INTERNET_OPTION_SETTINGS_CHANGED = 39
)

func SetSystemProxy(addr string) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Internet Settings`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()

	if err := k.SetDWordValue("ProxyEnable", 1); err != nil {
		return err
	}
	if err := k.SetStringValue("ProxyServer", addr); err != nil {
		return err
	}
	_ = k.SetStringValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>")

	NotifyWinINet()
	log.Printf("[Saturn Client] Windows System Proxy automatically ENABLED on %s", addr)
	return nil
}

func ClearSystemProxy() error {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Internet Settings`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()

	_ = k.SetDWordValue("ProxyEnable", 0)
	NotifyWinINet()
	log.Printf("[Saturn Client] Windows System Proxy automatically DISABLED and restored.")
	return nil
}

func NotifyWinINet() {
	_, _, _ = procInternetSetOption.Call(0, uintptr(INTERNET_OPTION_SETTINGS_CHANGED), 0, 0)
	_, _, _ = procInternetSetOption.Call(0, uintptr(INTERNET_OPTION_REFRESH), 0, 0)
}

func RegisterCloseHandler(cleanup func()) {
	handler := syscall.NewCallback(func(ctrlType uint32) uintptr {
		cleanup()
		return 0
	})
	_, _, _ = procSetConsoleCtrlHandler.Call(handler, 1)
}
