"""Accept SDK tool formatting differences without weakening APK identity checks."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from verify_release import check_badging, signing_certificate


class ReleaseVerificationTests(unittest.TestCase):
    metadata = {'versionName':'1.2.3-alpha','versionCode':60}
    badging = "package: name='app.aixs.mobile' versionCode='60' versionName='1.2.3-alpha'\nminSdkVersion:'26'\ntargetSdkVersion:'35'\nnative-code: 'arm64-v8a'\n"

    def test_android_and_termux_aapt_minimum_sdk_labels_are_supported(self):
        check_badging(self.badging, self.metadata)
        check_badging(self.badging.replace('minSdkVersion:', 'sdkVersion:'), self.metadata)

    def test_wrong_package_or_version_cannot_be_published(self):
        for old, new in (('app.aixs.mobile','wrong.app'),("versionCode='60'","versionCode='59'"),('1.2.3-alpha','1.2.2-alpha')):
            with self.subTest(value=new), self.assertRaisesRegex(ValueError, 'package/version'):
                check_badging(self.badging.replace(old,new),self.metadata)

    def test_wrong_or_additional_architecture_is_rejected(self):
        for value in ("'x86_64'", "'arm64-v8a' 'x86_64'", ''):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'ARM64'):
                check_badging(self.badging.replace("'arm64-v8a'",value),self.metadata)

    def test_minimum_and_target_sdk_are_verified_independently(self):
        for old,new in (("minSdkVersion:'26'","minSdkVersion:'28'"),("targetSdkVersion:'35'","targetSdkVersion:'34'")):
            with self.subTest(value=new), self.assertRaises(ValueError):
                check_badging(self.badging.replace(old,new),self.metadata)

    def test_sdk_and_termux_apksigner_formats_resolve_to_the_same_identity(self):
        cert='a'*64
        for label in ('Signer #1', 'V2 Signer:'):
            self.assertEqual(signing_certificate(label+' certificate SHA-256 digest: '+cert),cert)
        output='\n'.join('V'+str(n)+' Signer: certificate SHA-256 digest: '+cert for n in (1,2))
        self.assertEqual(signing_certificate(output),cert)

    def test_missing_or_different_signing_identities_are_rejected(self):
        for output in ('', 'certificate SHA-256 digest: '+'a'*64+'\ncertificate SHA-256 digest: '+'b'*64):
            with self.assertRaisesRegex(ValueError,'signing identity'):signing_certificate(output)


if __name__=='__main__':unittest.main()
