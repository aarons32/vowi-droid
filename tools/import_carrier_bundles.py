#!/usr/bin/env python3
"""Copy each carrier's IKE and ESP proposals from the iPhone carrier bundles into carrier_profiles.json.

The iPhone offers an ePDG exactly the proposal its carrier bundle lists
(`TechSettings.IKE.Proposals` and `TechSettings.ChildSAs.FirstChild.ChildProposals`),
one algorithm per transform type. This script reads every `<Carrier>.bundle/carrier.plist`
under the bundles folder, maps Apple's algorithm names to the IKEv2 transform IDs
VowiDroid implements, and writes the result into the matching profiles of
`app/src/main/assets/carrier_profiles.json` as `ike.exact_proposal`, `esp.exact_proposal`
and `tunnel`. Profiles that no bundle matches are left untouched. Standard library only.
The bundles carry no SMS centre numbers; those live in `app/src/main/assets/sms_centers.json`,
a hand-kept table by home network that this script never reads or writes.

Apple name                     IKEv2 transform                  profile value
AES-128 / AES-256              ENCR_AES_CBC (12) + key length   encryption "aes-cbc", key_bits 128 / 256
PRF SHA1-160                   PRF_HMAC_SHA1 (2)                prf "hmac-sha1"
PRF SHA2-256 / -384 / -512     PRF_HMAC_SHA2_256/384/512 (5/6/7)  prf "hmac-sha2-256" / "-384" / "-512"
integrity SHA1-96              AUTH_HMAC_SHA1_96 (2)            integrity "hmac-sha1-96"
integrity SHA2-256/-384/-512   AUTH_HMAC_SHA2_256_128 (12),     integrity "hmac-sha2-256-128" / "-384-192" / "-512-256"
                               _384_192 (13), _512_256 (14)
DHGroup n                      D-H group n (MODP 2, 5, 14, 15, 16, 18)   dh_group n

A bundle is matched to a profile through the SIM keys both were built from:
`<MCC><MNC>[_GID1-<hex>][_GID2-<hex>][_ID-<ICCID prefix>]`, read from the folder's
symlinks and each bundle's `SupportedSIMs`, against the profile's `home_plmns`,
`gid1_prefixes`, `gid2_prefixes` and `iccid_prefixes`. As on the iPhone, a key
without a GID or ICCID qualifier covers any SIM on that PLMN, and the most specific
key wins; the PLMN that ends the profile id breaks ties between PLMNs, and a
profile whose best candidates still disagree is left alone.

Usage:
  python3 -I tools/import_carrier_bundles.py [--bundles DIR] [--profiles FILE] [--dry-run] [--verbose]
"""

from __future__ import annotations

import argparse
import base64
import collections
import json
import os
import plistlib
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PROFILES = os.path.join(HERE, "..", "app", "src", "main", "assets", "carrier_profiles.json")
DEFAULT_BUNDLES = os.path.join(HERE, "..", "..", "iPhone")

ENCRYPTION = {"AES-128": 128, "AES-256": 256}
PRF = {"SHA1-160": "hmac-sha1", "SHA2-256": "hmac-sha2-256", "SHA2-384": "hmac-sha2-384", "SHA2-512": "hmac-sha2-512"}
INTEGRITY = {
    "SHA1-96": "hmac-sha1-96",
    "SHA2-256": "hmac-sha2-256-128",
    "SHA2-384": "hmac-sha2-384-192",
    "SHA2-512": "hmac-sha2-512-256",
}
DH_GROUPS = {2, 5, 14, 15, 16, 18}

KEY_RE = re.compile(
    r"^(?P<plmn>\d{5,6})"
    r"(?:_GID1-(?P<gid1>[0-9A-Fa-f]+))?"
    r"(?:_GID2-(?P<gid2>[0-9A-Fa-f]+))?"
    r"(?:_ID-(?P<iccid>\d+))?$"
)

# The order profile keys are written in, so a reviewer sees new blocks next to "ike".
PROFILE_KEY_ORDER = ["id", "match", "match_any", "identity", "privacy", "route", "epdg", "ike", "esp", "tunnel", "ims"]
PRIVACY_KEY_ORDER = ["idi", "eap", "anonymous_username", "realm", "encryption_certificate", "key_identifier", "update_url"]
EPDG_CERTIFICATE_KEY_ORDER = ["required", "hostname", "authority", "ca"]
IKE_KEY_ORDER = ["proposal", "advertise_eap_only", "exact_proposal"]


def canonical_plmn(value):
    """MCC plus a three-digit MNC, the form CarrierProfiles.kt compares."""
    text = str(value).strip().replace("/", "")
    if len(text) == 5:
        return text[:3] + "0" + text[3:]
    return text if len(text) == 6 else ""


class SimKey:
    __slots__ = ("plmn", "gid1", "gid2", "iccid")

    def __init__(self, plmn, gid1="", gid2="", iccid=""):
        self.plmn = canonical_plmn(plmn)
        self.gid1 = (gid1 or "").lower()
        self.gid2 = (gid2 or "").lower()
        self.iccid = iccid or ""

    @classmethod
    def parse(cls, name):
        m = KEY_RE.match(name.strip())
        if not m:
            return None
        return cls(m["plmn"], m["gid1"], m["gid2"], m["iccid"])

    def __repr__(self):
        parts = [self.plmn]
        if self.gid1:
            parts.append("GID1-" + self.gid1.upper())
        if self.gid2:
            parts.append("GID2-" + self.gid2.upper())
        if self.iccid:
            parts.append("ID-" + self.iccid)
        return "_".join(parts)


def prefix_related(a, b):
    return a.startswith(b) or b.startswith(a)


def match_score(selector, key, iccid_prefixes):
    """How well a profile selector fits one bundle key; None when they contradict each other."""
    if not selector.plmn or selector.plmn != key.plmn:
        return None
    score = 10
    for ours, theirs in ((selector.gid1, key.gid1), (selector.gid2, key.gid2)):
        if ours and theirs:
            if not prefix_related(ours, theirs):
                return None
            score += 5 if ours == theirs else 4
        elif theirs:
            # The key names an MVNO group the profile does not: narrower than the profile.
            score -= 1
        # A key without the qualifier covers every group on the PLMN, as it does on the iPhone.
    if selector.iccid and key.iccid:
        if not prefix_related(selector.iccid, key.iccid):
            return None
        score += 5 if selector.iccid == key.iccid else 4
    elif key.iccid:
        score -= 1
    elif selector.iccid and any(prefix_related(selector.iccid, p) for p in iccid_prefixes):
        score += 2
    return score


class Bundle:
    def __init__(self, name, plist, folder=None):
        self.name = name
        self.folder = folder
        self.keys = []
        self.iccid_prefixes = [str(p) for p in plist.get("MatchingICCIDPrefixes") or []]
        tech = plist.get("TechSettings") or {}
        ike = tech.get("IKE") or {}
        self.ike_proposals = ike.get("Proposals") or []
        child = ((tech.get("ChildSAs") or {}).get("FirstChild") or {})
        self.child_proposals = child.get("ChildProposals") or []
        self.ipsec_mtu = tech.get("IPSecMTU")
        self.natt_enabled = ike.get("NATTKeepAliveEnabled")
        self.natt_interval = None
        for key, value in ike.items():
            if key.lower() == "nattkeepaliveinterval":
                self.natt_interval = value
        self.dpd_enabled = ike.get("DeadPeerDetectionEnabled")
        self.notes = []  # what was narrowed or dropped, for the summary
        self.ike = self._ike_proposal()
        self.esp = self._esp_proposal()
        self.tunnel = self._tunnel()
        self.privacy = self._privacy(tech, plist)
        self.epdg_certificate = self._epdg_certificate(ike)

    @property
    def has_proposals(self):
        return bool(self.ike_proposals or self.child_proposals)

    @property
    def contributes(self):
        """Whether the bundle has anything a profile can take."""
        return self.has_proposals or bool(self.privacy) or bool(self.epdg_certificate)

    def _first(self, value, field):
        """Apple lists some fields as arrays; the iPhone offers them all, this stack one, so the first wins."""
        if isinstance(value, list):
            distinct = []
            for item in value:
                if item not in distinct:
                    distinct.append(item)
            if len(distinct) > 1:
                self.notes.append(f"{field} {distinct} narrowed to {distinct[0]!r}")
            return distinct[0] if distinct else None
        return value

    def _ike_proposal(self):
        if not self.ike_proposals:
            return None
        if len(self.ike_proposals) > 1:
            self.notes.append(f"{len(self.ike_proposals)} IKE proposals; the first is used")
        raw = self.ike_proposals[0]
        enc = self._first(raw.get("EncryptionAlgorithm"), "IKE EncryptionAlgorithm")
        prf = self._first(raw.get("PRFAlgorithm"), "IKE PRFAlgorithm")
        integrity = self._first(raw.get("IntegrityAlgorithm"), "IKE IntegrityAlgorithm")
        dh = self._first(raw.get("DHGroup"), "IKE DHGroup")
        lifetime = raw.get("Lifetime")
        unsupported = []
        if enc not in ENCRYPTION:
            unsupported.append(f"encryption {enc!r}")
        if prf not in PRF:
            unsupported.append(f"PRF {prf!r}")
        if integrity not in INTEGRITY:
            unsupported.append(f"integrity {integrity!r}")
        if not isinstance(dh, int) or dh not in DH_GROUPS:
            unsupported.append(f"DH group {dh!r}")
        if unsupported:
            self.notes.append("IKE proposal not imported: unsupported " + ", ".join(unsupported))
            return None
        proposal = {"encryption": "aes-cbc", "key_bits": ENCRYPTION[enc], "prf": PRF[prf], "integrity": INTEGRITY[integrity], "dh_group": dh}
        if isinstance(lifetime, int) and lifetime > 0:
            proposal["lifetime_seconds"] = lifetime
        return proposal

    def _esp_proposal(self):
        if not self.child_proposals:
            return None
        if len(self.child_proposals) > 1:
            self.notes.append(f"{len(self.child_proposals)} ESP proposals; the first is used")
        raw = self.child_proposals[0]
        enc = self._first(raw.get("EncryptionAlgorithm"), "ESP EncryptionAlgorithm")
        integrity = self._first(raw.get("IntegrityAlgorithm"), "ESP IntegrityAlgorithm")
        dh = self._first(raw.get("DHGroup"), "ESP DHGroup")
        lifetime = raw.get("Lifetime")
        unsupported = []
        if enc not in ENCRYPTION:
            unsupported.append(f"encryption {enc!r}")
        if integrity not in INTEGRITY:
            unsupported.append(f"integrity {integrity!r}")
        if unsupported:
            self.notes.append("ESP proposal not imported: unsupported " + ", ".join(unsupported))
            return None
        proposal = {"encryption": "aes-cbc", "key_bits": ENCRYPTION[enc], "integrity": INTEGRITY[integrity]}
        if dh is not None:
            if isinstance(dh, int) and dh in DH_GROUPS:
                proposal["dh_group"] = dh
            else:
                self.notes.append(f"ESP PFS group {dh!r} unsupported; omitted")
        if isinstance(lifetime, int) and lifetime > 0:
            proposal["lifetime_seconds"] = lifetime
        return proposal

    def _tunnel(self):
        tunnel = {}
        if isinstance(self.ipsec_mtu, int) and 576 <= self.ipsec_mtu <= 1500:
            tunnel["ipsec_mtu"] = self.ipsec_mtu
        if self.natt_enabled is not False and isinstance(self.natt_interval, int) and 1 <= self.natt_interval <= 600:
            tunnel["natt_keepalive_seconds"] = self.natt_interval
        if isinstance(self.dpd_enabled, bool):
            tunnel["dpd_enabled"] = self.dpd_enabled
        return tunnel or None

    def _privacy(self, tech, plist):
        """IMSI privacy on the ePDG path: anonymous IKE identity and the encrypted EAP identity (patent US10833876B2)."""
        method = str(tech.get("WiFiCallingIdentityProtectionMethod") or "").strip()
        ike = tech.get("IKE") or {}
        local = str(ike.get("LocalIdentifier") or "").strip()
        encrypted = plist.get("EncryptedIdentity") if isinstance(plist.get("EncryptedIdentity"), dict) else {}
        privacy = {}
        certificate = encrypted.get("Certificate")
        if isinstance(certificate, bytes):
            certificate = base64.b64encode(certificate).decode("ascii")
        if isinstance(certificate, str) and certificate.strip():
            privacy["encryption_certificate"] = "".join(certificate.split())
        if local.lower().startswith("anonymous@") or (method == "EncryptedIdentity" and "encryption_certificate" in privacy):
            privacy["idi"] = "anonymous"
        if method == "EncryptedIdentity" and "encryption_certificate" in privacy:
            privacy["eap"] = "encrypted"
        elif method == "EncryptedIdentity":
            self.notes.append("EncryptedIdentity without a certificate; the identity stays in the clear")
        if encrypted.get("AnonymousUsername"):
            privacy["anonymous_username"] = str(encrypted["AnonymousUsername"]).strip()
        realm = str(encrypted.get("NAIRealm") or "").strip().lstrip("@")
        if not realm and local.lower().startswith("anonymous@") and "$" not in local:
            realm = local.split("@", 1)[1]
        if realm:
            privacy["realm"] = realm.lower()
        if encrypted.get("KeyIdentifier"):
            privacy["key_identifier"] = str(encrypted["KeyIdentifier"]).strip()
        if encrypted.get("UpdateURL"):
            privacy["update_url"] = str(encrypted["UpdateURL"]).strip()
        # A certificate alone (no protection method, no anonymous identity) is kept for the day the carrier asks.
        return privacy or None

    def _epdg_certificate(self, ike):
        """The ePDG's certificate check, with the carrier CA the bundle ships, for carriers whose settings validate it."""
        method = str(ike.get("AuthenticationMethod") or "")
        if not (ike.get("ValidateRemoteCertificate") or method in ("Certificate", "ECDSA384")):
            return None
        certificate = {"required": True}
        if ike.get("RemoteCertificateHostname"):
            certificate["hostname"] = str(ike["RemoteCertificateHostname"]).strip().lower()
        if ike.get("RemoteCertificateAuthorityName"):
            certificate["authority"] = str(ike["RemoteCertificateAuthorityName"]).strip()
        path = os.path.join(self.folder, "CarrierCA.crt") if self.folder else None
        if path and os.path.isfile(path):
            with open(path, "rb") as fh:
                data = fh.read()
            if data.lstrip().startswith(b"-----BEGIN"):
                body = "".join(line for line in data.decode("ascii", "replace").splitlines() if line and not line.startswith("-----"))
                data = base64.b64decode(body)
            certificate["ca"] = base64.b64encode(data).decode("ascii")
        else:
            self.notes.append("certificate validation without a CarrierCA.crt; the system's CAs will have to do")
        return certificate

    def settings(self):
        """What this bundle contributes, for comparing candidates."""
        return json.dumps({"ike": self.ike, "esp": self.esp, "tunnel": self.tunnel, "privacy": self.privacy, "epdg_certificate": self.epdg_certificate}, sort_keys=True)


def load_bundles(root):
    bundles = {}
    for name in sorted(os.listdir(root)):
        if not name.endswith(".bundle"):
            continue
        path = os.path.join(root, name, "carrier.plist")
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "rb") as fh:
                plist = plistlib.load(fh)
        except Exception as error:  # a damaged plist is data, not a reason to stop
            print(f"warning: {name}: {error}", file=sys.stderr)
            continue
        bundle = Bundle(name[: -len(".bundle")], plist, os.path.join(root, name))
        for sim in plist.get("SupportedSIMs") or []:
            key = SimKey.parse(str(sim))
            if key:
                bundle.keys.append(key)
        bundles[name] = bundle
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if os.path.islink(path):
            target = os.path.basename(os.readlink(path).rstrip("/"))
            key = SimKey.parse(name)
            if key and target in bundles:
                bundles[target].keys.append(key)
    return list(bundles.values())


def profile_selectors(profile):
    entries = []
    if isinstance(profile.get("match"), dict):
        entries.append(profile["match"])
    entries.extend(e for e in profile.get("match_any") or [] if isinstance(e, dict))
    selectors = []
    for entry in entries:
        for plmn in entry.get("home_plmns") or []:
            for gid1 in entry.get("gid1_prefixes") or [""]:
                for gid2 in entry.get("gid2_prefixes") or [""]:
                    for iccid in entry.get("iccid_prefixes") or [""]:
                        selectors.append(SimKey(plmn, gid1, gid2, iccid))
    return selectors


def primary_plmn(profile):
    """The PLMN the generator put at the end of the profile id, such as 23410 in ipcc-o2-23410."""
    m = re.search(r"-(\d{5,6})$", str(profile.get("id", "")))
    return canonical_plmn(m.group(1)) if m else ""


def best_bundles(profile, bundles):
    """The bundles that fit this profile best, with the rank they reached.

    Rank: a key on the profile's primary PLMN, then the most specific key, then
    the bundle serving the most SIM groups of that PLMN (the network's own bundle
    over an MVNO's that shares its keys).
    """
    primary = primary_plmn(profile)
    best = {}
    matched_keys = collections.defaultdict(set)
    for selector in profile_selectors(profile):
        for bundle in bundles:
            for key in bundle.keys:
                score = match_score(selector, key, bundle.iccid_prefixes)
                if score is None:
                    continue
                matched_keys[bundle.name].add(repr(key))
                candidate = (selector.plmn == primary, score)
                if candidate > best.get(bundle.name, (False, -1)):
                    best[bundle.name] = candidate
    if not best:
        return [], (False, 0, 0)
    ranks = {name: (flag, score, len(matched_keys[name])) for name, (flag, score) in best.items()}
    top = max(ranks.values())
    by_name = {b.name: b for b in bundles}
    return [by_name[name] for name, rank in sorted(ranks.items()) if rank == top], top


def ordered(mapping, order):
    """The mapping with its known keys in a fixed order and any others after them, as they were."""
    result = {}
    for key in order:
        if key in mapping:
            result[key] = mapping[key]
    for key, value in mapping.items():
        if key not in result:
            result[key] = value
    return result


def apply(profile, bundle):
    """Writes the bundle's proposals into the profile; returns which blocks changed."""
    changed = []
    ike = dict(profile.get("ike") or {})
    if bundle.ike is not None:
        if ike.get("exact_proposal") != bundle.ike:
            changed.append("ike")
        ike["exact_proposal"] = dict(bundle.ike)
    if ike:
        profile["ike"] = ordered(ike, IKE_KEY_ORDER)
    if bundle.esp is not None:
        esp = dict(profile.get("esp") or {})
        if esp.get("exact_proposal") != bundle.esp:
            changed.append("esp")
        esp["exact_proposal"] = dict(bundle.esp)
        profile["esp"] = ordered(esp, ["exact_proposal"])
    if bundle.tunnel is not None:
        if profile.get("tunnel") != bundle.tunnel:
            changed.append("tunnel")
        profile["tunnel"] = ordered(bundle.tunnel, ["ipsec_mtu", "natt_keepalive_seconds", "dpd_enabled"])
    if bundle.privacy is not None:
        if profile.get("privacy") != bundle.privacy:
            changed.append("privacy")
        profile["privacy"] = ordered(dict(bundle.privacy), PRIVACY_KEY_ORDER)
    if bundle.epdg_certificate is not None:
        epdg = dict(profile.get("epdg") or {})
        if epdg.get("certificate") != bundle.epdg_certificate:
            changed.append("epdg_certificate")
        epdg["certificate"] = ordered(dict(bundle.epdg_certificate), EPDG_CERTIFICATE_KEY_ORDER)
        profile["epdg"] = ordered(epdg, ["hostname", "dns_hosts", "dns_client_subnet", "certificate"])
    return changed


# MVNO bundles leave IMSI privacy out, but the ePDG is the home network's, and it takes the home network's
# encrypted identity from an MVNO SIM too: seen live on 2026-10-19 with a Tello SIM on T-Mobile US.
PRIVACY_FROM_HOME_NETWORK = {
    "ss.epdg.epc.mnc260.mcc310.pub.3gppnetwork.org": "TMobile_US",
}


def inherit_home_network_privacy(profiles, bundles):
    """Gives profiles on a home network's ePDG that network's encrypted identity when their own bundle has none."""
    by_name = {b.name: b for b in bundles}
    changed = 0
    for profile in profiles:
        host = ((profile.get("epdg") or {}).get("hostname") or "").lower()
        source = PRIVACY_FROM_HOME_NETWORK.get(host)
        if not source or source not in by_name or not by_name[source].privacy:
            continue
        privacy = by_name[source].privacy
        if privacy.get("eap") != "encrypted" or profile.get("privacy") == privacy:
            continue
        profile["privacy"] = ordered(dict(privacy), PRIVACY_KEY_ORDER)
        changed += 1
    return changed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bundles", default=DEFAULT_BUNDLES, help="the Carrier Bundles/iPhone folder (default: ../../iPhone)")
    parser.add_argument("--profiles", default=DEFAULT_PROFILES, help="carrier_profiles.json to update")
    parser.add_argument("--dry-run", action="store_true", help="report, but leave the JSON alone")
    parser.add_argument("--verbose", action="store_true", help="list every profile and the bundle it took")
    args = parser.parse_args(argv)

    bundles = load_bundles(args.bundles)
    with_proposals = [b for b in bundles if b.contributes]
    with open(args.profiles, encoding="utf-8") as fh:
        document = json.load(fh)
    profiles = document.get("profiles") or []

    gained = collections.Counter()
    unchanged_profiles = 0
    no_bundle = []
    bundle_without_proposal = []
    ambiguous = []
    used_bundles = set()
    for index, profile in enumerate(profiles):
        candidates, rank = best_bundles(profile, bundles)
        if not candidates:
            no_bundle.append(profile.get("id", f"#{index}"))
            continue
        if not any(c.contributes for c in candidates):
            bundle_without_proposal.append((profile.get("id"), [c.name for c in candidates]))
            continue
        settings = {c.settings() for c in candidates}
        if len(settings) > 1:
            ambiguous.append((profile.get("id"), [c.name for c in candidates]))
            continue
        chosen = candidates[0]
        used_bundles.update(c.name for c in candidates)
        changed = apply(profile, chosen)
        profiles[index] = ordered(profile, PROFILE_KEY_ORDER)
        if changed:
            for block in changed:
                gained[block] += 1
            gained["profiles"] += 1
        else:
            unchanged_profiles += 1
        if args.verbose:
            print(f"{profile.get('id')}: {chosen.name} (rank {rank}) ike={chosen.ike} esp={chosen.esp} tunnel={chosen.tunnel}")

    inherited = inherit_home_network_privacy(profiles, bundles)

    if not args.dry_run:
        text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
        with open(args.profiles, "w", encoding="utf-8") as fh:
            fh.write(text)

    print(f"bundles: {len(bundles)} read, {len(with_proposals)} with proposals, privacy or certificate settings")
    print(f"profiles: {len(profiles)} in {os.path.relpath(args.profiles)}")
    print(f"  {gained['profiles']} profiles changed: {gained['ike']} IKE proposals, {gained['esp']} ESP proposals, {gained['tunnel']} tunnel settings, "
          f"{gained['privacy']} identity privacy blocks, {gained['epdg_certificate']} ePDG certificate checks"
          + (" (dry run, nothing written)" if args.dry_run else ""))
    print(f"  {unchanged_profiles} already carried their bundle's values")
    if inherited:
        print(f"  {inherited} profiles on a home network's ePDG took its encrypted identity")
    print(f"  {len(no_bundle)} matched no bundle (left untouched)" + (f": {', '.join(no_bundle)}" if args.verbose and no_bundle else ""))
    if bundle_without_proposal:
        print(f"  {len(bundle_without_proposal)} matched only bundles without proposals (left untouched)")
    if ambiguous:
        print(f"  {len(ambiguous)} matched several bundles with different proposals (left untouched):")
        for profile_id, names in ambiguous:
            print(f"    {profile_id}: {', '.join(names)}")
    unmapped = [b for b in with_proposals if b.name not in used_bundles]
    if unmapped:
        by_name = {b.name: b for b in bundles}
        print(f"bundles with proposals that no profile took ({len(unmapped)}):")
        for bundle in unmapped:
            plmns = {k.plmn for k in bundle.keys}
            twins = sorted(
                name for name in used_bundles
                if plmns & {k.plmn for k in by_name[name].keys} and by_name[name].settings() == bundle.settings()
            )
            keys = ", ".join(sorted({repr(k) for k in bundle.keys})[:4]) + (" ..." if len(set(map(repr, bundle.keys))) > 4 else "")
            note = f" (same values as {', '.join(twins[:3])}, which a profile took)" if twins else ""
            print(f"  {bundle.name}: {keys or 'no SIM keys'}{note}")
    noted = [b for b in with_proposals if b.notes]
    if noted:
        print(f"bundles whose proposals were narrowed or partly dropped ({len(noted)}):")
        for bundle in noted:
            for note in bundle.notes:
                print(f"  {bundle.name}: {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
