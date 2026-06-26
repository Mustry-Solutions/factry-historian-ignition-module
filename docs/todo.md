
# TODO

  ## Tag-path handling — port checks from the TimescaleDB sibling module

  Context: the sibling TimescaleDB historian module (../../mustry/timescaledb-historian-module)
  fixed several tag-path bugs. Most don't apply here because the stored keys differ:
  Factry stores `provider/tagPath` (2 segments, NO gateway); the sibling stores
  `gateway/provider/tagPath` (3 segments). But these two are worth checking in THIS repo:

  [] Check `if (tag.startsWith(prov + "/")) return tag;` in TagPathUtil.queryPathToStoredPath
       This is the same "provider == first folder" heuristic the sibling removed. It can
       misfire for a TYPED query whose tag's first folder literally equals the provider
       name (sibling repro: `[default]default/sub1/tagname1`): it returns the tag as-is
       instead of `prov/tag` → lookup miss → 0 rows. Normal tags are unaffected
       (e.g. `default/FactrySim/ii1`, first folder `FactrySim` != provider `default`);
       only ones where folder == provider hit it.
       To verify: add a unit test in TagPathUtilTest — query path
       `sys:X:/prov:default:/tag:default/sub/x` should map to `default/default/sub/x`.
       If it returns `default/sub/x`, it's the same bug → remove the heuristic.

  [] Check driver-schema (`drv:`) handling in TagPathUtil
       The sibling added `extractSysProv()` to also parse the framework's normalized
       `drv:gateway:provider` form, not just `sys:`/`prov:`. Here we only read `prov:`,
       so if the framework ever hands us a `drv:` path, `prov` comes back null and the
       measurement is mis-routed. Capture a real path in storagePathToStoredPath /
       queryPathToStoredPath to see whether `drv:`-schema paths occur; if so, port the
       driver-schema parsing.

  Not applicable here (recorded so we don't re-investigate):
   - Remote-historian source-gateway problem (sibling has a gateway segment to mismatch;
     we don't). Plan here: use two separate collectors, one per source gateway, so the
     originating gateway is distinguished at the collector level, not in the stored key.
   - getOriginalPath() switch — we only need `prov`+`tag`; low value.
   - Annotations — not implemented here.

  [] So metadata flows one direction only: 
         Factry → Ignition create tag with metadata in Ignition 
         Other direction: we can't change the metadata, but we can send what we have

  [] Setup automation (setup-factry.sh)
  
  [] Support legacy system.tag.* historian methods (currently PyList error in S&F bridge)
    - system.tag.storeTagHistory
    - system.tag.queryTagHistory
    - system.tag.queryTagCalculations


---------------
  some bugs:
    - check the default values if they work with the new release 8.3.6
  
  
  
   
   
  



