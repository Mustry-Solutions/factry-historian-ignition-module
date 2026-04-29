def doPost(request, session):
	try:
		body = request["data"]
		if hasattr(body, "decode"):
			body = body.decode("utf-8")
		elif not isinstance(body, str):
			body = str(body)
		parsed = system.util.jsonDecode(body)
		t = str(type(parsed))
		k = str(list(parsed.keys())) if hasattr(parsed, "keys") else "no keys"
		s = str(parsed)[:500]
		return {"json": {"debug_type": t, "debug_keys": k, "debug_str": s}}
	except:
		import traceback
		return {"json": {"success": False, "error": traceback.format_exc()}}
