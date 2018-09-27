(ns quantum.test
  (:require [doo.runner
              :refer-macros [doo-tests doo-all-tests]]
  #?@(:clj [[clojure.test :as test]
            [environ.core
              :refer [env]]])
            [quantum.core.print :as pr]
            [quantum.test.ai.core]
            [quantum.test.apis.amazon.cloud-drive.auth]
            [quantum.test.apis.amazon.cloud-drive.core]
            [quantum.test.apis.google.auth]
            [quantum.test.apis.intuit.mint]
            [quantum.test.apis.microsoft.azure.core]
            [quantum.test.apis.pinterest.core]
            [quantum.test.apis.quip.core]
            [quantum.test.apis.twitter.core]
            [quantum.test.apis.twitter.driver]
            [quantum.test.auth.core]
            [quantum.test.browser.core]
            [quantum.test.compile.core]
            [quantum.test.compile.transpile.core]
            [quantum.test.compile.transpile.from.java]
            [quantum.test.compile.transpile.javaesque]
            [quantum.test.compile.transpile.to.c-sharp]
            [quantum.test.compile.transpile.to.core]
            [quantum.test.compile.transpile.to.java]
            [quantum.test.compile.transpile.util]
            [quantum.test.core.analyze.clojure.core]
            [quantum.test.core.analyze.clojure.predicates]
            [quantum.test.core.analyze.clojure.transform]
            [quantum.test.core.cache]
            [quantum.test.core.classes]
            [quantum.test.core.collections]
            [quantum.test.core.collections.core]
            [quantum.test.core.collections.diff]
            [quantum.test.core.collections.differential]
            [quantum.test.core.collections.generative]
            [quantum.test.core.collections.inner]
            [quantum.test.core.collections.map-filter]
            [quantum.test.core.collections.selective]
            [quantum.test.core.collections.sociative]
            [quantum.test.core.collections.tree]
          #_[quantum.test.core.collections.zippers]
            [quantum.test.core.compare]
            [quantum.test.core.convert]
            [quantum.test.core.convert.core]
            [quantum.test.core.convert.primitive]
            [quantum.test.core.core]
            [quantum.test.core.data.array]
            [quantum.test.core.data.bytes]
            [quantum.test.core.data.complex.dsv]
            [quantum.test.core.data.complex.json]
            [quantum.test.core.data.complex.xml]
            [quantum.test.core.data.hex]
            [quantum.test.core.data.map]
            [quantum.test.core.data.queue]
            [quantum.test.core.data.set]
            [quantum.test.core.data.string]
            [quantum.test.core.data.vector]
            [quantum.test.core.error]
            [quantum.test.core.fn]
            [quantum.test.core.graph]
            [quantum.test.core.io.compress]
            [quantum.test.core.io.core]
            [quantum.test.core.io.filesystem]
            [quantum.test.core.io.meta]
            [quantum.test.core.io.serialization]
            [quantum.test.core.io.transcode]
            [quantum.test.core.io.utils]
            [quantum.test.core.java]
            [quantum.test.core.lexical.core]
            [quantum.test.core.log]
            [quantum.test.core.logic]
            [quantum.test.core.loops]
            [quantum.test.core.macros]
            [quantum.test.core.macros.defnt]
            [quantum.test.core.macros.deftype]
            [quantum.test.core.macros.fn]
            [quantum.test.core.macros.optimization]
            [quantum.test.core.macros.protocol]
            [quantum.test.core.macros.reify]
            [quantum.test.core.macros.transform]
            [quantum.test.core.meta.debug]
            [quantum.test.core.nondeterministic]
            [quantum.test.core.ns]
            [quantum.test.core.numeric]
            [quantum.test.core.numeric.convert]
            [quantum.test.core.numeric.exponents]
            [quantum.test.core.numeric.misc]
            [quantum.test.core.numeric.operators]
            [quantum.test.core.numeric.predicates]
            [quantum.test.core.numeric.trig]
            [quantum.test.core.numeric.truncate]
            [quantum.test.core.paths]
            [quantum.test.core.print]
            [quantum.test.core.process]
            [quantum.test.core.reducers]
            [quantum.test.core.reducers.fold]
            [quantum.test.core.reducers.reduce]
            [quantum.test.core.reflect]
            [quantum.test.core.resources]
            [quantum.test.core.spec]
            [quantum.test.core.string]
            [quantum.test.core.string.encode]
            [quantum.test.core.string.find]
            [quantum.test.core.string.format]
            [quantum.test.core.string.regex]
            [quantum.test.core.string.semantic]
            [quantum.test.core.system]
            [quantum.test.core.thread]
            [quantum.test.core.async]
            [quantum.test.core.time.core]
            [quantum.test.core.type]
            [quantum.test.core.type.core]
            [quantum.test.core.type.mime]
            [quantum.test.core.vars]
            [quantum.test.db.datomic]
            [quantum.test.db.datomic.core]
            [quantum.test.db.datomic.defs]
            [quantum.test.db.datomic.fns]
            [quantum.test.db.datomic.shard]
            [quantum.test.deploy.repack]
            [quantum.test.financial.core]
            [quantum.test.localization.core]
            [quantum.test.measure.convert]
            [quantum.test.measure.core]
            [quantum.test.media.imaging.convert]
            [quantum.test.media.imaging.ocr]
            [quantum.test.net.client.impl]
            [quantum.test.net.core]
            [quantum.test.net.http]
          #_[quantum.test.net.server.middleware]
          #_[quantum.test.net.server.router]
            [quantum.test.net.url]
            [quantum.test.net.websocket]
            [quantum.test.nlp.core]
            [quantum.test.nlp.stem.impl.porter]
            [quantum.test.numeric.core]
          #_[quantum.test.numeric.statistics]
            [quantum.test.numeric.optimization]
            [quantum.test.parse.core]
            [quantum.test.security.core]
            [quantum.test.security.cryptography]
            [quantum.test.ui.components]
            [quantum.test.ui.core]
            [quantum.test.ui.features]
            [quantum.test.ui.revision]
            [quantum.test.ui.style.color]
            [quantum.test.ui.style.css.core]
            [quantum.test.ui.style.css.defaults]
            [quantum.test.validate.core]
            [quantum.test.validate.domain]
            [quantum.test.validate.regex]))

(pr/js-println "======= QUANTUM TEST NS LOADED. =======")

#?(:cljs (println "======= RUNNING CLOJURESCRIPT TESTS ======="))

#?(:cljs (doo-all-tests #"quantum.test.+"))
