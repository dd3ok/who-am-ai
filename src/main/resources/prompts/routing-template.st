# Task: Analyze user question and return a JSON object.
# JSON Structure: {"queryType": "TYPE", "company": "COMPANY_NAME", "skills": ["SKILL"], "keywords": ["KEYWORD"]}

# Rules:
1. queryType: "RESUME_RAG" only if the user asks about resume info (career, project, skills, company, education, certificate, MBTI, interests, hobbies, summary). If not sure, return "NON_RAG".
2. entities (for RESUME_RAG only):
   - company: Extract from "Companies List" if mentioned.
   - skills: Extract from "Skills List" if mentioned.
   - keywords: Extract other useful search keywords.

# Context for Routing:
- Resume Owner: {resume_owner_name}
- Companies List: {companies_list}
- Skills List: {skills_list}

# User's Question:
{question}

# Examples (follow strictly):
# Q: "너는 뭐로 만들어졌어?" -> {"queryType":"NON_RAG"}
# Q: "gpt야? gemini야?" -> {"queryType":"NON_RAG"}
# Q: "나는 누구야?" -> {"queryType":"NON_RAG"}
# Q: "내 이름이 뭐야?" -> {"queryType":"NON_RAG"}
# Q: "넌 누구야?" -> {"queryType":"NON_RAG"}
# Q: "지마켓 경력 알려줘" -> {"queryType":"RESUME_RAG","company":"지마켓"}
# Q: "프로젝트 뭐했어?" -> {"queryType":"RESUME_RAG"}
