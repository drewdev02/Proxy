#!/usr/bin/env python3
"""Pruebas del simulador del servicio de cuotas (tools/test_cuotas.py): que hable SOAP como el de la UCI.

    python3 tools/test_cuotas_test.py
"""

import os
import shutil
import sys
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import test_cuotas as cuotas  # noqa: E402


def state(**overrides):
    values = dict(user="andrey", password="secreta7", quota=2000, used=1420.53, level="Profesores", grow=0.0,
                  fields=cuotas.DEFAULT_FIELDS)
    values.update(overrides)
    return cuotas.QuotaState(**values)


class QuotaServiceTest(unittest.TestCase):
    def start(self, **overrides):
        server, url = cuotas.start_server(state(**overrides))
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return url

    def test_the_right_account_gets_its_quota(self):
        url = self.start()

        quota, used, level, _ = cuotas.ask(url, "andrey", "secreta7")

        self.assertEqual((2000, 1420.53, "Profesores"), (quota, used, level))

    def test_a_wrong_password_is_a_soap_fault(self):
        url = self.start()

        with self.assertRaises(cuotas.QuotaFault) as caught:
            cuotas.ask(url, "andrey", "otra")
        self.assertIn("incorrectos", caught.exception.message)

    def test_the_answer_is_read_by_position_whatever_the_field_names(self):
        url = self.start(fields=("a", "b", "c"))

        self.assertEqual((2000, 1420.53, "Profesores"), cuotas.ask(url, "andrey", "secreta7")[:3])

    def test_consumption_can_grow_with_each_query_up_to_the_quota(self):
        url = self.start(quota=100, used=95.0, grow=3.0)

        used = [cuotas.ask(url, "andrey", "secreta7")[1] for _ in range(3)]

        self.assertEqual([95.0, 98.0, 100.0], used)

    def test_a_request_without_the_soap_action_is_rejected(self):
        url = self.start()
        request = urllib.request.Request(url, data=cuotas.build_request("andrey", "secreta7"), method="POST",
                                         headers={"Content-Type": "text/xml"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)
        with caught.exception as error:
            self.assertEqual(500, error.code)
            with self.assertRaises(cuotas.QuotaFault):
                cuotas.parse_response(error.read())

    def test_the_wsdl_describes_the_method(self):
        url = self.start()

        with urllib.request.urlopen(url + "?wsdl", timeout=5) as response:
            wsdl = response.read().decode()

        self.assertIn('operation name="ObtenerCuota"', wsdl)
        self.assertIn(cuotas.SOAP_ACTION, wsdl)

    def test_special_characters_in_the_password_travel_escaped(self):
        url = self.start(password="a<b&c")

        self.assertEqual(2000, cuotas.ask(url, "andrey", "a<b&c")[0])

    @unittest.skipIf(shutil.which("openssl") is None, "sin openssl")
    def test_over_https_with_its_own_certificate(self):
        server, url = cuotas.start_server(state(), tls=True)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        with self.assertRaises(Exception):
            cuotas.ask(url, "andrey", "secreta7")  # certificado propio: sin --insecure no se acepta
        self.assertEqual(2000, cuotas.ask(url, "andrey", "secreta7", insecure=True)[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
