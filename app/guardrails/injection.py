from app.guards.injection import *
def detect_injection_regex(text):
    return rule_reasons(text)
