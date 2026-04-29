def doPost(request, session):
	try:
		body = request['data']
		if hasattr(body, 'decode'):
			body = body.decode('utf-8')
		elif not isinstance(body, str):
			body = str(body)
		data = system.util.jsonDecode(body)

		paths = [str(p) for p in data["paths"]]
		timestamps_ms = data["timestamps"]
		properties = data["properties"]

		dates = [system.date.fromMillis(long(ts)) for ts in timestamps_ms]

		# properties must be a dict (PyDictionary), not a list
		if isinstance(properties, list):
			# If caller sent a list of dicts, merge into a single dict
			merged = {}
			for p in properties:
				if isinstance(p, dict):
					merged.update(p)
			properties = merged

		system.historian.storeMetadata(
			paths=paths,
			timestamps=dates,
			properties=properties
		)

		return {"json": {"success": True}}

	except Exception, e:
		import traceback
		return {"json": {"success": False, "error": str(e), "trace": traceback.format_exc()}}
