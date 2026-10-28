#!/usr/bin/env python3
"""Sustituye ${VAR} por su valor del entorno en el texto de entrada (como envsubst).
Uso: sustituir.py < entrada.yaml > salida.yaml
Falla si queda alguna variable sin definir, para no aplicar manifiestos incompletos.
"""
import os
import re
import sys

faltantes = set()


def reemplazo(m):
    nombre = m.group(1)
    if nombre in os.environ:
        return os.environ[nombre]
    faltantes.add(nombre)
    return m.group(0)


texto = re.sub(r"\$\{(\w+)\}", reemplazo, sys.stdin.read())
if faltantes:
    sys.exit("variables sin definir: " + ", ".join(sorted(faltantes)))
sys.stdout.write(texto)
