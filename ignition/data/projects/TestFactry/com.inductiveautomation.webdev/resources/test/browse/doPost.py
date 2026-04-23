def doPost(request, session):
	try:
		body = request['data']
		if hasattr(body, 'decode'):
			body = body.decode('utf-8')
		elif not isinstance(body, str):
			body = str(body)
		data = system.util.jsonDecode(body)

		path = str(data["path"])

		kwargs = {"rootPath": path}
		if "nameFilters" in data and data["nameFilters"]:
			kwargs["nameFilters"] = [str(f) for f in data["nameFilters"]]

		results = system.historian.browse(**kwargs)

		nodes = []
		for r in results.getResults():
			node = {
				"path": str(r.getPath()) if r.getPath() is not None else None,
				"type": str(r.getType()) if r.getType() is not None else None,
				"hasChildren": r.hasChildren(),
			}
			nodes.append(node)

		return {"json": {"success": True, "results": nodes, "count": len(nodes)}}

	except Exception, e:
		import traceback
		return {"json": {"success": False, "error": str(e), "trace": traceback.format_exc()}}
