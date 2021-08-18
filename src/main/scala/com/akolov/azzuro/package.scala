package com.akolov
import zio._ 

package object azzuro {
  type AIO[A] = ZIO[ZEnv, AzzuroError, A]
}
