# FILE: tools/apk_endpoints.py
# ROLE: SCRIPT
#
# PURPOSE: Extract Yi Cloud HTTP endpoint constants from the official APK by scanning the
#          DEX string pool, without decompiling. Produces the evidence behind
#          docs/reference/yi-cloud-endpoints.md.
#
# WHY THIS EXISTS: reverse-engineering the camera/event endpoints is the main open blocker in
#   PROJECT_STATUS 0, and live capture is blocked on the emulator (VT-x off in firmware). A
#   string-pool scan needs no device, so it can narrow the candidate set before any capture.
#
# USAGE:
#   py -3.11 tools\apk_endpoints.py
#   py -3.11 tools\apk_endpoints.py --apk _apk\extracted\base.apk --filter alert
#
# NOTE ON THE LEADING BYTE: DEX string constants are length-prefixed and that prefix byte is
#   part of the ASCII match window, so recovered constants often carry one junk leading
#   character (commonly '!'). It is stripped and the value re-validated against the endpoint
#   shape before being reported.
#
# LIMITATION: this recovers constants only. Paths assembled at runtime (notably the recording
#   playback URL) are invisible here. Presence proves the client can call a path; it does not
#   prove parameters, signing, or the response envelope.

import argparse
import re
import sys
import zipfile

DEFAULT_APK = r"_apk\extracted\base.apk"

# Printable ASCII runs are the dex string constants we can recover without a dex parser.
PRINTABLE = re.compile(rb"[ -~]{4,300}")

# Endpoint shape: a versioned or service-namespace path. '/v1' is deliberately absent - the
# real scheme has no /v1 prefix (see docs/reference/yi-cloud-endpoints.md).
ENDPOINT = re.compile(
    r"^/(?:v\d+|vas|bs|event|touch|orderpay|api|oauth2|open)"
    r"/[A-Za-z0-9_\-./{}]+$"
)

NAMESPACES = ("v2", "v3", "v4", "v5", "v8", "vas", "bs", "event", "touch", "orderpay")


def scan(apk_path, want_filter=None):
    """Return {endpoint: set(dex_names)} for every endpoint constant found in the APK."""
    found = {}
    with zipfile.ZipFile(apk_path) as archive:
        for name in archive.namelist():
            if not name.endswith(".dex"):
                continue
            dex_id = name.replace("classes", "").replace(".dex", "")
            for match in PRINTABLE.finditer(archive.read(name)):
                value = match.group().decode("ascii", "ignore").lstrip("!")
                if not ENDPOINT.match(value) or len(value) > 90:
                    continue
                if want_filter and want_filter.lower() not in value.lower():
                    continue
                found.setdefault(value, set()).add(dex_id)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", default=DEFAULT_APK, help="path to base.apk")
    parser.add_argument("--filter", default=None, help="substring filter, e.g. 'alert'")
    args = parser.parse_args()

    found = scan(args.apk, args.filter)

    print(f"TOTAL: {len(found)} endpoint constants\n")
    for endpoint in sorted(found):
        dexes = ",".join(sorted(x for x in found[endpoint] if x))
        print(f"{endpoint}\t{dexes}")

    # Namespace census: a quick way to see which API generations the client actually uses.
    census = {}
    for endpoint in found:
        top = endpoint.strip("/").split("/")[0]
        census[top] = census.get(top, 0) + 1
    print("\nnamespace census:", ", ".join(
        f"{k}={v}" for k, v in sorted(census.items(), key=lambda kv: -kv[1])
    ))


if __name__ == "__main__":
    sys.exit(main())