(ns user
  (:require [clojure.math :as math]))

(defmulti factorial identity)

(defmethod factorial 0 [_] 1)

(defmethod factorial :default [num]
  (* num (factorial (dec num))))


(defmulti calc-area (fn [shape]
                 (:type shape)))

(defmethod calc-area :circle
  [shape]
  (let [r (:radius shape)] 
    (* math/PI (* r r))))

(defmethod calc-area :sqaure
  [shape]
  (let [width (:width shape)]
    (* width width)))

(defmethod calc-area :rectangle
  [shape]
  (let [width (:width shape)
        height (:height shape)]
    (* width height)))




(comment 
  (factorial 0)
  (factorial 1)
  (factorial 2)
  (factorial 3)
  (factorial 4)

  (calc-area {:type :circle
              :radius 10} )
  
  (calc-area {:type :square
              :witdh 6
              :height 6})
  
  (calc-area {:type :rectangle
              :witdh 6
              :height 4})
  )
