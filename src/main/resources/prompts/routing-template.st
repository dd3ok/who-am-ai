### 작업
- 추가 텍스트 없이 JSON 한 개만 반환한다.
- 형태: {"queryType":"NON_RAG"|"RESUME_RAG","company":"<회사 또는 null>","skills":["..."],"keywords":["..."]}

### 규칙
- 이력서 관련(경력/프로젝트/스킬/회사/학력/자격증/MBTI/관심사/취미/요약)만 RESUME_RAG, 그 외는 NON_RAG.
- RESUME_RAG일 때:
  - company: Companies List에서 언급된 회사만.
  - skills: Skills List에서 언급된 스킬만.
  - keywords: 그 외 유용 키워드.
- 애매하면 NON_RAG로.

### 라우팅 컨텍스트
- Resume Owner: {resume_owner_name}
- Companies List: {companies_list}
- Skills List: {skills_list}

### 사용자 질문
{question}

### 예시
- "너는 뭐로 만들어졌어?" -> {"queryType":"NON_RAG"}
- "지마켓 경력 알려줘" -> {"queryType":"RESUME_RAG","company":"지마켓"}
- "프로젝트 뭐했어?" -> {"queryType":"RESUME_RAG"}
