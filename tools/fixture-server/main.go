// KVN's disposable H2 CONNECT fixture. Uses only the pinned Husi distro.
package main

import (
	"context"
	"flag"
	"fmt"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/naive"
	"github.com/sagernet/sing/common/json"
	"github.com/xchacha20-poly1305/husi/libcore/v2/distro"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	if err := run(); err != nil {
		// A parser error may contain configuration fragments; never print it.
		fmt.Fprintln(os.Stderr, "fixture core failed")
		os.Exit(1)
	}
}
func run() error {
	args := os.Args[1:]
	if len(args) > 0 && args[0] == "run" {
		args = args[1:]
	}
	flags := flag.NewFlagSet("fixture", flag.ContinueOnError)
	path := flags.String("c", "", "private config file")
	if err := flags.Parse(args); err != nil {
		return err
	}
	data, err := os.ReadFile(*path)
	if err != nil {
		return err
	}
	inbounds := distro.InboundRegistry()
	// Husi is a client distro: Naive inbound registration is intentionally disabled.
	// Enable the server only in this isolated reference executable.
	naive.RegisterInbound(inbounds)
	ctx := box.Context(context.Background(), inbounds, distro.OutboundRegistry(),
		distro.EndpointRegistry(), distro.DNSTransportRegistry(), distro.ServiceRegistry(), distro.CertificateProviderRegistry())
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		return err
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		return err
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		return err
	}
	stopped, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	<-stopped.Done()
	return nil
}
