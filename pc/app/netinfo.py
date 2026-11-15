"""识别 iPhone / 安卓 USB 共享网卡和实际 IPv4 网段。音频仍通过本地网络协议传输。"""
import ctypes
import ipaddress
import sys

IS_WINDOWS = sys.platform == 'win32'
IF_TYPE_ETHERNET = 6
IF_TYPE_WIFI = 71


if IS_WINDOWS:
    from ctypes import wintypes

    class _SockAddrIn(ctypes.Structure):
        _fields_ = [('family', ctypes.c_ushort), ('port', ctypes.c_ushort), ('addr', ctypes.c_ubyte * 4)]

    class _SocketAddress(ctypes.Structure):
        _fields_ = [('sockaddr', ctypes.c_void_p), ('length', ctypes.c_int)]

    class _Unicast(ctypes.Structure):
        pass

    _Unicast._fields_ = [('Length', wintypes.ULONG), ('Flags', wintypes.DWORD),
                         ('Next', ctypes.POINTER(_Unicast)), ('Address', _SocketAddress),
                         ('PrefixOrigin', ctypes.c_int), ('SuffixOrigin', ctypes.c_int),
                         ('DadState', ctypes.c_int), ('ValidLifetime', wintypes.ULONG),
                         ('PreferredLifetime', wintypes.ULONG), ('LeaseLifetime', wintypes.ULONG),
                         ('OnLinkPrefixLength', ctypes.c_ubyte)]

    class _Adapter(ctypes.Structure):
        pass

    _Adapter._fields_ = [
        ('Length', wintypes.ULONG), ('IfIndex', wintypes.DWORD),
        ('Next', ctypes.POINTER(_Adapter)), ('AdapterName', ctypes.c_char_p),
        ('FirstUnicast', ctypes.POINTER(_Unicast)), ('FirstAnycast', ctypes.c_void_p),
        ('FirstMulticast', ctypes.c_void_p), ('FirstDns', ctypes.c_void_p),
        ('DnsSuffix', ctypes.c_wchar_p), ('Description', ctypes.c_wchar_p), ('FriendlyName', ctypes.c_wchar_p),
        ('PhysicalAddress', ctypes.c_ubyte * 8), ('PhysicalAddressLength', wintypes.ULONG),
        ('Flags', wintypes.ULONG), ('Mtu', wintypes.ULONG), ('IfType', wintypes.DWORD),
        ('OperStatus', wintypes.DWORD),
    ]


def adapters():
    """本机所有有 IPv4 地址的网卡：[{name, desc, type, up, ips}]。非 Windows 返回空列表。"""
    if not IS_WINDOWS:
        return []
    iphlpapi = ctypes.windll.iphlpapi
    size = wintypes.ULONG(32 * 1024)
    flags = 0x2 | 0x4 | 0x8            # 跳过 anycast / multicast / DNS
    buf = None
    for _ in range(4):
        buf = ctypes.create_string_buffer(size.value)
        rc = iphlpapi.GetAdaptersAddresses(2, flags, None, buf, ctypes.byref(size))
        if rc == 0:
            break
        if rc != 111:                  # ERROR_BUFFER_OVERFLOW 以外就放弃
            return []
    else:
        return []
    out = []
    p = ctypes.cast(buf, ctypes.POINTER(_Adapter))
    while p:
        a = p.contents
        ips = []
        networks = []
        u = a.FirstUnicast
        while u:
            sa = u.contents.Address.sockaddr
            if sa:
                s = ctypes.cast(sa, ctypes.POINTER(_SockAddrIn)).contents
                if s.family == 2:
                    ip = '.'.join(str(b) for b in s.addr)
                    ips.append(ip)
                    prefix = u.contents.OnLinkPrefixLength
                    if 0 < prefix <= 32:
                        networks.append(str(ipaddress.ip_network('%s/%d' % (ip, prefix), strict=False)))
            u = u.contents.Next
        out.append({'name': a.FriendlyName or '', 'desc': a.Description or '', 'type': a.IfType,
                    'up': a.OperStatus == 1, 'ips': ips, 'networks': networks})
        p = a.Next
    return out


def is_iphone_usb(ad):
    """是不是 iPhone 的 USB 共享网卡。驱动叫 Apple Mobile Device Ethernet。"""
    text = (ad.get('desc', '') + ' ' + ad.get('name', '')).lower()
    return ad.get('type') == IF_TYPE_ETHERNET and ('apple' in text and ('ethernet' in text or 'mobile device' in text))


def is_android_usb(ad):
    """常见安卓共享驱动；不把普通 USB 以太网转接器当成手机。"""
    if ad.get('type') != IF_TYPE_ETHERNET or is_iphone_usb(ad):
        return False
    text = (ad.get('desc', '') + ' ' + ad.get('name', '')).lower()
    return any(marker in text for marker in (
        'rndis', 'remote ndis', 'usb tether', 'usb 网络共享', 'usb网络共享',
        'remote sharing', 'remote internet sharing', 'usb ncm', 'mobile ncm',
    )) or ('android' in text and ('usb' in text or 'ethernet' in text))


def usb_network_adapters():
    """只返回当前启用的共享网卡；尚未取得地址时仍可显示等待状态。"""
    result = []
    for ad in adapters():
        platform = 'iphone' if is_iphone_usb(ad) else 'android' if is_android_usb(ad) else None
        if not platform or not ad['up']:
            continue
        ips = []
        for ip in ad['ips']:
            try:
                address = ipaddress.IPv4Address(ip)
                if not (address.is_link_local or address.is_loopback or address.is_unspecified or address.is_multicast):
                    ips.append(ip)
            except ValueError:
                continue
        networks = []
        for cidr in ad.get('networks', []):
            try:
                network = ipaddress.IPv4Network(cidr, strict=False)
                if network.prefixlen and any(ipaddress.IPv4Address(ip) in network for ip in ips):
                    networks.append(str(network))
            except ValueError:
                continue
        result.append(dict(ad, platform=platform, ips=ips, networks=networks))
    return result


def iphone_usb_ips():
    """iPhone USB 共享网络里电脑自己的 IPv4 地址（已连上才有）。"""
    ips = []
    try:
        for ad in adapters():
            if ad['up'] and is_iphone_usb(ad):
                for ip in ad['ips']:
                    a = ipaddress.ip_address(ip)
                    if not a.is_link_local and not a.is_loopback:
                        ips.append(ip)
    except Exception:
        pass
    return ips


def iphone_usb_present():
    """是否有 iPhone 的 USB 共享网卡（哪怕还没拿到地址）。"""
    try:
        return any(is_iphone_usb(ad) for ad in adapters())
    except Exception:
        return False


if __name__ == '__main__':
    for ad in adapters():
        print(('* ' if is_iphone_usb(ad) else '  ') + '%-28s %-44s type=%-3s up=%s %s'
              % (ad['name'], ad['desc'], ad['type'], ad['up'], ad['ips']))
