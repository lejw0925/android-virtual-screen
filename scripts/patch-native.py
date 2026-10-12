#!/usr/bin/env python3
"""Apply auditable Android path and system DNS integration to pinned sources."""
from pathlib import Path
root = Path(__file__).resolve().parent.parent
path = root / '.native-src/cloudflared-2025.8.1/cmd/cloudflared/tunnel/login.go'
source = path.read_text()
source = source.replace('checkForExistingCert()', 'checkForExistingCert(c.String("origincert"))', 1)
source = source.replace('func checkForExistingCert() (string, bool, error) {', '''func checkForExistingCert(originCert string) (string, bool, error) {
    // Android apps have no Unix home directory. Honor the existing official
    // --origincert option for login as well as management commands.
    if originCert != "" {
        path, err := homedir.Expand(originCert)
        if err != nil { return "", false, err }
        if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil { return path, false, err }
        info, err := os.Stat(path)
        if err == nil && info.Size() > 0 { return path, true, nil }
        if err != nil && !os.IsNotExist(err) { return path, false, err }
        return path, false, nil
    }
''')
source = source.replace('"os"', '"os"\n\t"path/filepath"') if '"path/filepath"' not in source else source
path.write_text(source)
# Cloudflared creates a second resolver for origin/edge diagnostics. On Android
# there is no localhost DNS daemon, so point that resolver at the app bridge too.
dns_path = root / '.native-src/cloudflared-2025.8.1/ingress/origins/dns.go'
dns_source = dns_path.read_text()
if '"os"' not in dns_source: dns_source = dns_source.replace('"net/netip"', '"net/netip"\n\t"os"')
old = 'func NewDNSResolverService(dialer ingress.OriginDialer, logger *zerolog.Logger, metrics Metrics) *DNSResolverService {\n\treturn &DNSResolverService{\n\t\taddresses: []netip.AddrPort{defaultResolverAddr},'
new = 'func NewDNSResolverService(dialer ingress.OriginDialer, logger *zerolog.Logger, metrics Metrics) *DNSResolverService {\n\taddresses := []netip.AddrPort{defaultResolverAddr}\n\tstatic := false\n\tif value := os.Getenv(\"VIRTUAL_SCREEN_DNS_ADDRESS\"); value != \"\" {\n\t\tif parsed, err := netip.ParseAddrPort(value); err == nil && parsed.Addr().IsLoopback() { addresses = []netip.AddrPort{parsed}; static = true }\n\t}\n\treturn &DNSResolverService{\n\t\taddresses: addresses,\n\t\tstatic: static,'
dns_source = dns_source.replace(old, new)
dns_path.write_text(dns_source)
resolver = '''//go:build android

package main

import (
    "context"
    "net"
    "os"
    "time"
)

// Go's SRV/TXT resolver otherwise reads /etc/resolv.conf, absent on Android.
// The parent supplies a loopback bridge to Android DnsResolver.rawQuery.
func init() {
    address := os.Getenv("VIRTUAL_SCREEN_DNS_ADDRESS")
    host, _, err := net.SplitHostPort(address)
    if err != nil || host != "127.0.0.1" { return }
    resolver := &net.Resolver{
        PreferGo: true,
        Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
            dialer := net.Dialer{Timeout: 10 * time.Second}
            return dialer.DialContext(ctx, network, address)
        },
    }
    net.DefaultResolver = resolver
}
'''
for relative in ('cloudflared-2025.8.1/cmd/cloudflared', 'frp-0.65.0/cmd/frpc'):
    (root / '.native-src' / relative / 'virtual_screen_dns_android.go').write_text(resolver)
