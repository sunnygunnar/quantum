(ns
  ^{:doc "Cryptographic/encryption functions like hashing and digests.

          Possibly should just alias some better library for this."
    :attribution "Alex Gunnarson"}
  quantum.core.cryptography
  (:refer-clojure :exclude [hash])
  (:require-quantum
    [ns bytes set fn logic str type num macros log convert err coll arr bin rand])
  (:require [byte-transforms :as bt])
#?(:clj
  (:import
    [java.security     MessageDigest DigestInputStream SecureRandom AlgorithmParameters]
    [java.io           InputStream   ByteArrayInputStream]
    [java.nio          ByteBuffer]
    [java.nio.charset  Charset StandardCharsets]
    [javax.crypto      Mac SecretKeyFactory SecretKey Cipher]
    [javax.crypto.spec SecretKeySpec PBEKeySpec IvParameterSpec]
    [org.apache.commons.codec.binary Base32 Base64] ; Unnecessary
    org.mindrot.jbcrypt.BCrypt
    com.lambdaworks.crypto.SCryptUtil
    org.bouncycastle.crypto.engines.ThreefishEngine
    org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
    [org.bouncycastle.crypto BlockCipher BufferedBlockCipher])))

; Nested |let-mutable| :
    ; ClassCastException java.lang.Long cannot be cast to proteus.Containers$L


#?(:clj
  (def ^sun.nio.cs.UTF_8 utf-8
    (Charset/forName "UTF-8")))

; ===== ENCODE =====

#?(:clj (def available-encoders #{:base32 :base64}))

; (encode "abc" :base64)
; Inaccurate: use _ instead of -, etc., and chop off trailing '='
; (:clj (defalias encode bt/encode))
; (:clj (defalias decode bt/decode))

(defnt ^"[B" encode32
  ([^integer? x]
    (throw+ (Err. :not-implemented "Encode32 not implemented for |integer|" x)))
  ([^bytes? x] (.encode (Base32.) x))
  ([x] (encode32 (arr/->bytes-protocol x))))

(defnt ^"[B" encode64
  ([^integer? x]
    (Base64/encodeInteger (num/->big-integer x)))
  ([^bytes? x] (.encode (java.util.Base64/getEncoder) x))
  ; TODO make so Object gets protocol if reflection
  ([x] (encode64 (arr/->bytes-protocol x))))

(defn encode [k obj]
  (condp = k
    :base32 (encode32 obj)
    :base64 (encode64 obj)
    :base64-string
      (let [^"[B" encoded (encode64 obj)]
        (String. encoded StandardCharsets/ISO_8859_1))
    (throw+ (Err. nil "Unrecognized codec" k))))

; ===== DECODE =====

(defnt ^"[B" decode32
  ([^integer? x]
    (throw+ (Err. :not-implemented "Decode32 not implemented for |integer|" x)))
  ([^bytes? x] (.decode (Base32.) x))
  ([x] (decode32 (arr/->bytes-protocol x))))

(defnt ^"[B" decode64
  ([^bytes?  x] (.decode (java.util.Base64/getDecoder) x))
  ([^string? x] (-> x (.getBytes StandardCharsets/ISO_8859_1) decode64))
  ([         x] (-> x arr/->bytes-protocol decode64)))

(defnt ^Integer decode64-int
  ([^bytes? x] (int (Base64/decodeInteger x)))
  ([x] (decode64-int (arr/->bytes-protocol x))))

(defn ^"[B" decode [k obj]
  (condp = k
    :base32        (decode32 obj)
    :base64        (decode64 obj)
    :base64-string (let [^"[B" decoded (decode64 obj)]
                     (String. decoded StandardCharsets/ISO_8859_1))
    (throw+ (Err. nil "Unrecognized codec" k))))

(defn ^"[B" decode-int [k obj]
  (condp = k
    :base64 (decode64-int obj)
    (throw+ (Err. nil "Unrecognized codec" k))))

; ===== DIGEST =====

#?(:clj
(defn ^bytes digest
  "Creates a byte digest of the string @s according to the @md-type algorithm.
   Assumes UTF-8 encoding for string passed.

   WARNINGS:
     CMU Software Engineering Institute now says that MD5
     'should be considered cryptographically broken and
      unsuitable for further use.'
     SHA-1 has some possible vulnerabilities too.
     Most U.S. government applications now require the SHA-2
     family of hash functions.

     As for SHA-3, attackers can crack SHA-3 hashed passswords 8 times
     faster than SHA-2 hashed passwords -
     2 times faster because we need to halve the number of hash iterations
     and 4 times faster because of SHA-3 hardware being faster than SHA-2 hardware.

     SHA-3, like SHA-2, is not intended for use as a password hash function.

     Don't encrypt passwords symmetrically. Other things, maybe, but not passwords.
     Anything that can be asymmetrically encrypted instead of symmetrically, do it."
  {:todo "Allow more hashing functions"}
  [^Keyword hash-type ^String s]
  {:pre [(splice-or hash-type = :md5 :sha-256)]}
  (let [^String            hash-type-str
          (-> hash-type name str/upper-case)
        ^MessageDigest     md
          (MessageDigest/getInstance hash-type-str)
        ^InputStream       in (conv/->input-stream s)
        ^DigestInputStream dis
          (DigestInputStream. in md)
        ^bytes             digested (.digest md)]
    (.close in)
    (.close dis)
    digested)))

#?(:clj
  (defn ^String hex-digest
    "Gets the digest of a string in hexadecimal.
     Assumes UTF-8 encoding for string passed."
    [^Key hash-type ^String s]
    (->> s
         (digest hash-type)
         bytes/bytes-to-hex)))

; ===== HASH =====

; Fast hashes kill security.
#?(:clj
(def available-hashers
  (set/union
    #{:crc32 :crc64
      :md2 ; 1989
      ; md4
      :md5 ; 1992
      ; md6 
      ; As of 2015, no example of a SHA-1 collision has been published yet — Wikipedia
      :sha1   :sha-1-hmac
      :sha256 :sha-256-hmac
      :sha384
      :sha512
      :murmur32 :murmur64 :murmur128
      :adler32
      :pbkdf2
      :bcrypt
      :scrypt}
    (into #{} (bt/available-hash-functions)))))

#?(:clj
(defn sha-hmac ^"[B" [instance-str message secret]
  (throw-unless (and message secret)
    (Err. nil "Neither message nor secret can be nil" (kmap message secret)))
  (let [^Mac algo-instance (Mac/getInstance instance-str)
        ^SecretKey secret-key (SecretKeySpec. (->bytes secret) instance-str)]
    (.init    algo-instance secret-key)
    (.doFinal algo-instance (->bytes message)))))

#?(:clj
(defn pbkdf2
 "Recommended by NIST:
    http://csrc.nist.gov/publications/nistpubs/800-132/nist-sp800-132.pdf

  OWASP: Use when FIPS certification or enterprise support on many platforms is required.
  OWASP: Apple uses this algorithm with 10000 iterations for its iTunes passwords."
  ([^String s & [salt iterations key-length]]
   (let [salt       (if (nnil? salt) (->bytes salt) (rand/rand-bytes 128))
         iterations (or iterations 100000)
         k (PBEKeySpec. (.toCharArray s)
             salt iterations
             (or key-length 192))
         hashed
           (->> (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA1")
                (<- .generateSecret k)
                (.getEncoded)
                (encode :base64)
                ->str)
         salt (->str salt)
         iterations (->> iterations (encode :base64) ->str)]
     (kmap hashed salt iterations)))))

#?(:clj
(defn ^String bcrypt
  "OWASP: Use where PBKDF2 or scrypt support is not available.
   @work-factor: the log2 of the number of hashing iterations to apply"
  ([raw & [work-factor]]
    (BCrypt/hashpw raw (BCrypt/gensalt (or work-factor 11))))))

#?(:clj
(defn ^String scrypt
  "OWASP: Use when resisting any and all hardware accelerated attacks is necessary but support isn’t."
  [^String s & [cpu-cost ram-cost parallelism]]
  (SCryptUtil/scrypt s
    (num/exp 2 (or cpu-cost 15)) ; Milliseconds?
    (or ram-cost    8)   ; Gigabytes?
    (or parallelism 1)))) ; Cores

(defn hash
  ([obj] (hash :murmur64 obj))
  ([algorithm obj & [opts]]
    (condp = algorithm
      :clojure      (core/hash obj)
      :sha-1-hmac   (sha-hmac "HmacSHA1"   obj (:secret opts))
      :sha-256-hmac (sha-hmac "HmacSHA256" obj (:secret opts))
      :pbkdf2 (pbkdf2 obj (:salt        opts)
                          (:iterations  opts)
                          (:key-length  opts))
      :bcrypt (bcrypt obj (:work-factor opts))
      :scrypt (scrypt obj (:cpu-cost    opts)
                          (:ram-cost    opts)
                          (:parallelism opts))
      (bt/hash obj algorithm opts))))

; ===== HASH COMPARE =====

(defn secure=
  "Test whether two sequences of characters or bytes are equal in a way that
   protects against timing attacks. Note that this does not prevent an attacker
   from discovering the *length* of the data being compared."
  {:source "weavejester/crypto.equality"}
  [a b]
  (let [a (map int a)
        b (map int b)]
    (if (and a b (= (count a) (count b)))
        (zero? (core/reduce bit-or (map bit-xor a b)))
        false)))

(defn hash-match? [algo test encrypted]
  (condp = algo 
    :pbkdf2
      (if-not (map? encrypted)
        (throw+ (Err. nil "Encrypted must be map." encrypted))
        (let [salt       (decode     :base64 (:salt       encrypted))
              iterations (decode-int :base64 (:iterations encrypted))]
          (secure= (:hashed encrypted)
                   (hash :pbkdf2 encrypted iterations salt))))
    :bcrypt (BCrypt/checkpw   test encrypted)
    :scrypt (SCryptUtil/check test encrypted)))

; Not secure
#?(:clj (defalias hash= bt/hash=))

; ===== SYMMETRIC ENCRYPTION =====

(defn encrypt-param*
  ([type key-] (encrypt-param* type key- true))
  ([type key- tweak]
    (let [encrypt-param
            (condp = type
              :encrypt true
              :decrypt false
              (throw+ (Err. nil "Invalid encryption param" type)))
          _ (when (= type :decrypt)
              (throw-unless (and key- tweak)
                (Err. nil "Missing required parameters:" (kmap key- tweak))))]
      encrypt-param)))

(defn aes
  "With help from http://stackoverflow.com/questions/992019/java-256-bit-aes-password-based-encryption"
  {:tests '[(let [pass "password"
                  e (aes :encrypt "Hey! This is a secret message." pass)]
              (String.
                (aes :decrypt
                  (:encrypted e) pass
                  (:key e) (:salt e))))]}
  [type in ^String password & [key salt]]
  ;[{:keys [encrypted ^"[B" tweak]}]
  (let [encrypt? (encrypt-param* type password)
        ^SecretKeyFactory factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                          salt    (or salt (rand/rand-bytes true 128))
        ^KeySpec          spec    (PBEKeySpec. (.toCharArray password)
                                    salt
                                    (num/exp 2 16)
                                    (num/exp 2 8))
        ^SecretKey        secret  (-> factory 
                                      (.generateSecret spec)
                                      (.getEncoded)
                                      (SecretKeySpec. "AES"))]
    (if encrypt?
        (let [;  Encrypt the message.
              ^Cipher           cipher  (doto (Cipher/getInstance "AES/CBC/PKCS5Padding")
                                              (.init Cipher/ENCRYPT_MODE secret))
              ^AlgorithmParameters params  (.getParameters cipher)
              ; to avoid reflection
              ^IvParameterSpec     iv-spec (-> params (.getParameterSpec IvParameterSpec))
              ^"[B" iv          (.getIV iv-spec)
              ^"[B" encrypted   (-> cipher (.doFinal (->bytes in "UTF-8")))]
          {:key iv :encrypted encrypted :salt salt}) ; The salt doesn't need to be kept secret
       ; Decrypt the message, given derived key and initialization vector.
       ; Encrypted should be bytes
       (-> (Cipher/getInstance "AES/CBC/PKCS5Padding")
           (doto (.init Cipher/DECRYPT_MODE secret (IvParameterSpec. key)))
           (.doFinal in)))))

; On decryption, the SecretKey is regenerated in exactly the same way,
;using using the password with the same salt and iteration parameters.
;Initialize the cipher with this key and the initialization vector stored with the message:

(defn threefish
  {:todo  ["Truncate decrypted text — reverse search for NUL char in last block and truncate from there"]
   :tests '[(let [e (threefish :encrypt "Hey! This is a secret message.")]
              (String.
                (threefish :decrypt
                  (:encrypted e)
                  (:key e) (:tweak e))))]}
  [type in & [key- tweak bits]]
  (let [encrypt?   (encrypt-param* type key- tweak)
        bits       (or bits 512)
        block-size (/ bits 8)
        key-  (or key-  (rand/rand-longs (-> bits (/ 8) (/ 8))))
        tweak (or tweak (rand/rand-longs 2))
        engine (-> (ThreefishEngine. bits)
                   (doto
                     (.init encrypt? key- tweak))
                   (BufferedBlockCipher.))
        in-bytes (->bytes in)
        in-f (byte-array (-> in-bytes count (/ block-size)
                             num/ceil int (* block-size)))
        _ (System/arraycopy in-bytes 0 in-f 0 (count in-bytes))
        out (byte-array (count in-f))
        len-processed
          (.processBytes engine
            in-f 0 (count in-f) out 0)
        _ (.doFinal engine out len-processed)]
    (condp = type
      :encrypt {:encrypted out :key key- :tweak tweak}
      :decrypt out)))

(defn encrypt
  {:tests '[(let [opts {:password "Alex"}
                  e (encrypt :aes "Hey! A message!" opts)]
              (decrypt :aes (:encrypted e) (merge opts e)))]}
  [algo obj & [{:keys [key tweak salt password]}]]
  (condp = algo
    :aes       (aes       :encrypt obj password key salt)
    :threefish (threefish :encrypt obj key tweak)))

(defn decrypt [algo obj & [{:keys [key tweak salt password]}]]
  (condp = algo
    :aes       (aes       :decrypt obj password key salt)
    :threefish (threefish :decrypt obj key tweak)))

; (import '(java.io FileInputStream FileOutputStream OutputStream))
; (import '(java.security KeyStore KeyFactory))
; (import 'java.security.cert.Certificate)
; (import 'javax.crypto.spec.SecretKeySpec)
; (import 'java.security.spec.PKCS8EncodedKeySpec)

; (let [^String filePathToStore (io/parse-dirs-keys [:resources "ssl-keys.cljx"])
;       ^"[C" password (.toCharArray "password")
;       ^KeyStore ks  (KeyStore/getInstance "JKS")
;       ^KeyFactory kf (KeyFactory/getInstance "RSA")
;       pk-bytes (.getBytes "SECRETS")
;       ^PrivateKey pk (.generatePrivate kf (PKCS8EncodedKeySpec. pk-bytes));
;       _ (.load ks nil password)

;       secret-key (SecretKeySpec. pk-bytes, 0, (count "SECRETS"), "AES")
;       _ (.setKeyEntry ks "keyAlias" pk password, nil)
;       ^OutputStream fos (FileOutputStream. filePathToStore)
;      ]
;       (.store  ks fos, password)
;      (.close fos))


; (let [^String keystoreFilename (io/parse-dirs-keys [:resources "ssl-keys.cljx"])
;       ^"[C" password (.toCharArray "password")
;       ^String alias0 "alias"
;       ^FileInputStream fin (FileInputStream. keystoreFilename)
;       ^KeyStore keystore (KeyStore/getInstance "JKS")
;       _ (.load keystore fin password)
;       ^Certificate cert (.getCertificate keystore alias0)]
;       (println cert)
;       (.close fin))
