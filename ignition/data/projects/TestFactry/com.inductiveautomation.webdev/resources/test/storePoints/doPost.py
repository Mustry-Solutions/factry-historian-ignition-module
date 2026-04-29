def doPost(request, session):
	try:
		body = request['data']
		if hasattr(body, 'decode'):
			body = body.decode('utf-8')
		elif not isinstance(body, str):
			body = str(body)
		data = system.util.jsonDecode(body)

		paths = [str(p) for p in data["paths"]]
		values = data["values"]
		timestamps_ms = data["timestamps"]
		qualities = data.get("qualities", None)

		dates = [system.date.fromMillis(long(ts)) for ts in timestamps_ms]

		kwargs = {
			"paths": paths,
			"values": values,
			"timestamps": dates,
		}
		if qualities is not None:
			kwargs["qualities"] = [int(q) for q in qualities]

		system.historian.storeDataPoints(**kwargs)

		return {"json": {"success": True, "pointCount": len(values)}}

	except Exception, e:
		import traceback
		return {"json": {"success": False, "error": str(e), "trace": traceback.format_exc()}}
