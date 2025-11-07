package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.Resume

interface LoadResumePort {
    fun load(): Resume
}