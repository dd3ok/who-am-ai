package com.dd3ok.whoamai.infrastructure.adapter.`in`.web

//@RestController
//@RequestMapping("/admin")
//class ResumeIndexController(
//    private val resumeJsonLoader: ResumeJsonLoader,
//    private val mongoVectorAdapter: MongoVectorAdapter,
//    private val chatService: ChatService
//) {
//    @PostMapping("/reindex-resume")
//    suspend fun reindexResume(): ResponseEntity<String> {
//        val resume = resumeJsonLoader.loadResume()
//        val resumeChunks = chatService.generateResumeChunks(resume)
//        val count = mongoVectorAdapter.indexResume(resumeChunks)
//        return ResponseEntity.ok("총 $count개 resume chunk가 벡터 DB에 저장되었습니다.")
//    }
//}