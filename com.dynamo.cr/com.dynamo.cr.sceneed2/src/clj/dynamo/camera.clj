(ns dynamo.camera
  (:require [schema.macros :as sm]
            [schema.core :as s]
            [dynamo.types :as t]
            [internal.cache :refer [caching]])
  (:import [javax.vecmath Point3d Quat4d Matrix4d Vector3d Vector4d]
           [dynamo.types Camera Region]))

(def camera-view-matrix
  (caching
    (fn [camera]
      (let [pos (Vector3d. (.position camera))
            m   (doto (Matrix4d.)
                  .setIdentity
                  (.set (.rotation camera))
                  (.transpose)
                  (.transform pos))]
        (.transform m pos)
        (.negate pos)
        (.setColumn m 3 (.x pos) (.y pos) (.z pos) 1.0)
        m))))

(sm/defn camera-perspective-projection-matrix :- Matrix4d
  [camera :- Camera]
  (let [near   (.z-near camera)
        far    (.z-far camera)
        aspect (.aspect camera)
        fov    (.fov camera)

        ymax (* near (Math/tan (/ (* fov Math/PI) 360.0)))
        ymin (- ymax)
        xmin (* ymin aspect)
        xmax (* ymax aspect)

        x    (/ (* 2.0 near) (- xmax xmin))
        y    (/ (* 2.0 near) (- ymax ymin))
        a    (/ (+ xmin xmax) (- xmax xmin))
        b    (/ (+ ymin ymax) (- ymax ymin))
        c    (/ (- (+     near far)) (- far near))
        d    (/ (- (* 2.0 near far)) (- far near))
        m    (Matrix4d.)]
    (set! (. m m00) x)
    (set! (. m m01) 0.0)
    (set! (. m m02) a)
    (set! (. m m03) 0.0)

    (set! (. m m10) 0.0)
    (set! (. m m11) y)
    (set! (. m m12) b)
    (set! (. m m13) 0.0)

    (set! (. m m20) 0.0)
    (set! (. m m21) 0.0)
    (set! (. m m22) c)
    (set! (. m m23) d)

    (set! (. m m30) 0.0)
    (set! (. m m31) 0.0)
    (set! (. m m32) -1.0)
    (set! (. m m33) 0.0)
    m))

(sm/defn camera-orthographic-projection-matrix :- Matrix4d
  [camera :- Camera]
  (let [near   (.z-near camera)
        far    (.z-far camera)
        right  (/ (.fov camera) 2.0)
        left   (- right)
        bottom (/ left (.aspect camera))
        top    (/ right (.aspect camera))

        m      (Matrix4d.)]
    (set! (. m m00) (/ 2.0 (- right left)))
    (set! (. m m01) 0.0)
    (set! (. m m02) 0.0)
    (set! (. m m03) (/ (- (+ right left)) (- right left)))

    (set! (. m m10) 0.0)
    (set! (. m m11) (/ 2.0 (- top bottom)))
    (set! (. m m12) 0.0)
    (set! (. m m13) (/ (- (+ top bottom)) (- top bottom)))

    (set! (. m m20) 0.0)
    (set! (. m m21) 0.0)
    (set! (. m m22) (/ -2.0 (- far near)))
    (set! (. m m23) (/ (- (+ far near) (- far near))))

    (set! (. m m30) 0.0)
    (set! (. m m31) 0.0)
    (set! (. m m32) 0.0)
    (set! (. m m33) 1.0)
    m))

(def camera-projection-matrix
  (caching
    (fn [t near far aspect fov]
      (case t
        :perspective  (camera-perspective-projection-matrix near far aspect fov)
        :orthographic (camera-orthographic-projection-matrix near far aspect fov)))))

(sm/defn make-camera :- Camera
  [t :- (s/enum :perspective :orthographic)]
  (let [distance 44.0
        position (doto (Vector3d.) (.set 0.0 0.0 1.0) (.scale distance))
        rotation (doto (Quat4d.)   (.set 0.0 0.0 0.0 1.0))]
    (t/->Camera t position rotation 1 2000 1 30)))

(sm/defn camera-rotate :- Camera
  [camera :- Camera q :- Quat4d]
  (assoc camera :rotation (.mul (.rotation camera) (.normalize (Quat4d. q)))))

(sm/defn camera-move :- Camera
  [camera :- Camera x :- s/Num y :- s/Num z :- s/Num]
  (let [p (.position camera)]
    (set! (. p x) (+ x (. p x)))
    (set! (. p y) (+ y (. p y)))
    (set! (. p z) (+ z (. p z)))
    camera))

(sm/defn camera-set-position :- Camera
  [camera :- Camera x :- s/Num y :- s/Num z :- s/Num]
  (let [p (.position camera)]
    (set! (. p x) x)
    (set! (. p y) y)
    (set! (. p z) z)
    camera))

(sm/defn camera-project :- Point3d
  "Returns a point in device space (i.e., corresponding to pixels on screen)
   that the given point projects onto. The input point should be in world space."
  [camera :- Camera viewport :- Region point :- Point3d]
  (let [proj  (.projection-matrix camera)
        model (.view-matrix camera)
        in    (Vector4d. (.x point) (.y point) (.z point) 1.0)
        out   (Vector4d.)]
    (.transform model in out)
    (.transform proj out in)

    (assert (not= 0.0 (.w in)))
    (let [w (.w in)
          x (/ (.x in) w)
          y (/ (.y in) w)
          z (/ (.z in) w)

          rx (+ (.left viewport) (/ (* (+ 1 x) (.right  viewport)) 2))
          ry (+ (.top  viewport) (/ (* (+ 1 y) (.bottom viewport)) 2))
          rz (/ (+ 1 x) 2)

          device-y (- (.bottom viewport) (.top viewport) ry 1)]
      (Point3d. rx device-y rz))))
